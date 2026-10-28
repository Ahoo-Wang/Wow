/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.spi.json.WowJson

/**
 * The `wow_catalog` entries of one aggregate's tables ([CatalogEntry]), from the Manifest: the
 * aggregate's identity and ownership, and the `x-wow-sensitive`, `x-wow-mask` and `x-wow-reference` keywords of its
 * state's and its events' schemas in `$defs`, each at its path from the column that holds the value:
 *
 * | Table | Paths |
 * | --- | --- |
 * | `_state_events` | `state.<path>`, each event's `events.payload.<path>` |
 * | `_state` | `state.<path>` |
 * | `_events` | each event's `payload.<path>` |
 * | `_state_flat` | the flat column of a top-level property |
 *
 * A path goes through an object's properties (`.name`), a map's values (`.{key}`) and an array's items (no segment,
 * as a query path does); a `$ref` is followed, a recursive one not again; `allOf`, `anyOf` and `oneOf` branches are
 * all read. A value marked twice keeps its highest level.
 */
internal object CatalogEntries {
    const val STATE_EVENTS: String = "state_events"
    const val STATE: String = "state"
    const val EVENTS: String = "events"
    const val STATE_FLAT: String = "state_flat"

    private const val TENANT_COLUMN = "tenant_id"
    private const val OWNER_COLUMN = "owner_id"
    private const val SPACE_COLUMN = "space_id"

    fun of(manifest: Manifest, aggregate: AggregateManifest, schema: ClickHouseSinkSchema): List<CatalogEntry> {
        val marks = SchemaMarks(manifest.defs, schema.type.context)
        val state = marks.of(aggregate.state.schema, STATE, event = "", revision = 0)
        val events = aggregate.events.map { marks.of(it.schema, "", it.name, it.revision) }
        fun withPrefix(prefix: String): List<Mark> = events.flatten().map {
            it.copy(path = if (it.path.isEmpty()) prefix else "$prefix.${it.path}")
        }
        val columns = schema.expected.associate { it.name to it.columns.orEmpty() }
        fun entry(table: String, role: String, marked: List<Mark>): CatalogEntry = CatalogEntry(
            table = table,
            role = role,
            service = manifest.service?.name.orEmpty(),
            context = schema.type.context,
            aggregate = schema.type.name,
            ownership = aggregate.ownership.name,
            tenantColumn = TENANT_COLUMN,
            ownerColumn = if (aggregate.ownership == Ownership.OWNER) OWNER_COLUMN else "",
            spaceColumn = SPACE_COLUMN,
            sensitive = marked.mapNotNull { it.sensitive },
            references = marked.mapNotNull { it.reference }.distinct(),
            eventRevisions = aggregate.events.associate { it.name to it.revision },
            columnsHash = ClickHouseCatalog.columnsHash(columns.getValue(table)),
        )
        return buildList {
            if (schema.aggregate.history) {
                add(entry(schema.stateEvents, STATE_EVENTS, state + withPrefix("events.payload")))
            }
            add(entry(schema.state, STATE, state))
            if (schema.aggregate.history) {
                add(entry(schema.events, EVENTS, withPrefix("payload")))
            }
            add(entry(schema.stateFlat, STATE_FLAT, flat(state, schema.flatColumns)))
        }
    }

    /** The marks of the top-level properties that are flat columns, by the column's name. */
    private fun flat(state: List<Mark>, columns: List<FlatColumn>): List<Mark> {
        val byProperty = columns.associateBy { it.property }
        return state.mapNotNull { mark ->
            val property = mark.path.removePrefix("$STATE.").takeUnless { it == mark.path || '.' in it }
            property?.let(byProperty::get)?.let { mark.copy(path = it.name) }
        }
    }
}

/** What a schema says of the value at [path]: its [level] and [mask] if it is sensitive, the aggregate it refers to. */
internal data class Mark(
    val path: String,
    val level: String? = null,
    val mask: String = "",
    val event: String = "",
    val revision: Int = 0,
    val referenceContext: String? = null,
    val referenceAggregate: String? = null,
) {
    val sensitive: CatalogSensitive?
        get() = level?.let { CatalogSensitive(path, it, mask, event, revision) }

    val reference: CatalogReference?
        get() = referenceAggregate?.let { CatalogReference(path, referenceContext.orEmpty(), it) }
}

/** Reads the marks of a Manifest schema of [defs] ([CatalogEntries]); [context] is the one a reference omits. */
internal class SchemaMarks(private val defs: Map<String, Any?>, private val context: String) {
    /** The marks of the schema [ref] points at, its paths below [prefix], each of [event] at [revision]. */
    fun of(ref: String, prefix: String, event: String, revision: Int): List<Mark> {
        val found = mutableListOf<Mark>()
        walk(mapOf(REF to ref), prefix, emptySet()) { path, node ->
            mark(path, node, event, revision)?.let(found::add)
        }
        return merge(found)
    }

    private fun walk(node: Map<*, *>, path: String, resolving: Set<String>, visit: (String, Map<*, *>) -> Unit) {
        visit(path, node)
        (node[REF] as? String)?.takeIf { Manifest.isSchemaRef(it) }?.let { ref ->
            val key = Manifest.schemaKey(ref)
            val target = defs[key] as? Map<*, *>
            if (target != null && key !in resolving) {
                walk(target, path, resolving + key, visit)
            }
        }
        COMPOSITIONS.forEach { composition ->
            (node[composition] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { walk(it, path, resolving, visit) }
        }
        (node["properties"] as? Map<*, *>)?.forEach { (name, child) ->
            (child as? Map<*, *>)?.let { walk(it, join(path, name.toString()), resolving, visit) }
        }
        (node["items"] as? Map<*, *>)?.let { walk(it, path, resolving, visit) }
        (node["additionalProperties"] as? Map<*, *>)?.let { walk(it, join(path, KEY), resolving, visit) }
    }

    private fun mark(path: String, node: Map<*, *>, event: String, revision: Int): Mark? {
        val level = (node[SENSITIVE] as? Map<*, *>)?.get("level") as? String
        val reference = node[REFERENCE] as? Map<*, *>
        val aggregate = reference?.get("aggregateName") as? String
        if (level == null && aggregate == null) {
            return null
        }
        return Mark(
            path = path,
            level = level,
            mask = (node[MASK] as? Map<*, *>)?.let { WowJson.mapper.writeValueAsString(it) }.orEmpty(),
            event = event,
            revision = revision,
            referenceContext = aggregate?.let { reference["contextName"] as? String ?: context },
            referenceAggregate = aggregate,
        )
    }

    /** One mark per path: the highest level with its mask, and the first reference. */
    private fun merge(marks: List<Mark>): List<Mark> = marks.groupBy { it.path }.map { (_, same) ->
        val sensitive = same.filter { it.level != null }.maxByOrNull { LEVELS.indexOf(it.level) }
        val reference = same.firstOrNull { it.referenceAggregate != null }
        Mark(
            path = same.first().path,
            level = sensitive?.level,
            mask = sensitive?.mask.orEmpty(),
            event = same.first().event,
            revision = same.first().revision,
            referenceContext = reference?.referenceContext,
            referenceAggregate = reference?.referenceAggregate,
        )
    }

    private fun join(path: String, name: String): String = if (path.isEmpty()) name else "$path.$name"

    private companion object {
        const val REF = "\$ref"
        const val KEY = "{key}"
        const val SENSITIVE = "x-wow-sensitive"
        const val MASK = "x-wow-mask"
        const val REFERENCE = "x-wow-reference"
        val COMPOSITIONS = listOf("allOf", "anyOf", "oneOf")

        /** The sensitivity levels, lowest first (`SensitivityLevel`). */
        val LEVELS = listOf("DISPLAY", "CONFIDENTIAL")
    }
}
