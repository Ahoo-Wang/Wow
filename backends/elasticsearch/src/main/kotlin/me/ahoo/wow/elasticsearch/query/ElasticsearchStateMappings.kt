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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Conflicts
import co.elastic.clients.elasticsearch._types.SlicesCalculation
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

/**
 * The mapping of the fields of a payload whose index does not map it dynamically (a snapshot's `state`, an event's
 * `body`), from the payload's logical schema, the Manifest's: Elasticsearch indexes a field only once its index maps
 * it, so these are its query indexes.
 *
 * Each value maps by its logical type: a string to a `keyword` (capped at [ElasticsearchSnapshotIndex.KEYWORD_LIMIT]),
 * with a `text` field for full-text search when its schema marks it as prose ([QueryValueSchema.text]), a date string
 * to a `date`, an integer (an epoch included) to a `long`, a
 * decimal to a `double`, a boolean to a `boolean`, an object to its properties, an array of objects to a `nested`
 * field (so a predicate matches one element and an aggregation expands them), a map (an object of any keys) to a
 * `flattened` field, an array to its items' mapping. A value of several types, or of none known, is not mapped: it
 * stays in `_source`, unindexed.
 *
 * [manage] adds the fields an index lacks and reports those it maps otherwise (a field's type cannot change), and adds
 * the `text` field to a string the index maps as a `keyword` once its schema marks it; once it added some, it indexes
 * the documents written before them again ([rewrite]), so they are found by the new fields too. A field the index maps
 * already is otherwise left as it is: a `text` field whose marker was removed stays, and is no longer searched.
 */
internal class ElasticsearchStateMappings(
    private val client: ElasticsearchAsyncClient,
    private val mappingResolver: ElasticsearchIndexMappingResolver,
    /** Where the payload lies in each document. */
    private val payload: ElasticsearchPayload,
    /** How many documents a second [rewrite] indexes again, `-1` for no limit. */
    private val rewriteRate: Int = DEFAULT_REWRITE_RATE,
) {
    /** The logical schemas each index was found to map already, so a load maps nothing twice. */
    private val managed = ConcurrentHashMap<String, LogicalQuerySchema>()

    /**
     * Maps the payload fields of [schema] that [index] lacks, when [create] (`auto`), else only reports them
     * (`validate`): the fields it created, and those it found missing or mapped otherwise.
     */
    suspend fun manage(index: String, schema: LogicalQuerySchema, create: Boolean = true): QueryIndexReport {
        if (managed[index] === schema) return QueryIndexReport.NONE
        val payloadValue = payload.value(schema) ?: return QueryIndexReport.NONE
        val existing = mappingResolver.refresh(index)
        if (existing.provisional) return QueryIndexReport.NONE
        val plan = Plan(existing, payload.mergesVariants)
        val properties = plan.properties(payload.path, payloadValue)
        if (plan.created.isEmpty() || !create) {
            if (plan.invalid.isEmpty() && plan.created.isEmpty()) managed[index] = schema
            return QueryIndexReport(missing = plan.created, invalid = plan.invalid)
        }
        val mapping = payload.mapping(properties)
        client.indices().putMapping(
            PutMappingRequest.of { it.index(index).withJson(StringReader(WowJson.mapper.writeValueAsString(mapping))) },
        ).awaitElasticsearch()
        mappingResolver.evict(index)
        rewrite(index, plan.created)
        managed[index] = schema
        return QueryIndexReport(created = plan.created, invalid = plan.invalid)
    }

    /**
     * Indexes again, in the background, the documents of [index] written before [fields] were mapped, so that they are
     * found by them too: `_update_by_query` without a script, which keeps each `_source` as it is. It runs at
     * [rewriteRate] documents a second, in as many slices as the index has shards, so that a large index is not
     * rewritten at full speed beside its writes and queries (ES-5); a search of the new fields misses those documents
     * until the task ends. Returns the task's id: `GET _tasks/<id>` follows it, `POST _update_by_query/<id>/_rethrottle`
     * changes its rate. The client is told nothing of the task's end without polling it, so only its start is logged.
     */
    internal suspend fun rewrite(index: String, fields: List<String>): String {
        val task = client.updateByQuery {
            it.index(index)
                .conflicts(Conflicts.Proceed)
                .waitForCompletion(false)
                .requestsPerSecond(rewriteRate.toFloat())
                .slices { slices -> slices.computed(SlicesCalculation.Auto) }
        }.awaitElasticsearch().task()
        checkNotNull(task) { "Elasticsearch answered no task for the rewrite of [$index]." }
        log.info {
            "Elasticsearch index [$index] mapped $fields: its earlier documents are indexed again at " +
                "${if (rewriteRate < 0) "no limit" else "$rewriteRate a second"}, task [$task]; a search of these " +
                "fields misses them until the task ends."
        }
        return task
    }

    /** One walk of a payload's schema against an index's existing mapping. */
    private class Plan(private val existing: ElasticsearchIndexMapping, private val mergesVariants: Boolean) {
        val created = mutableListOf<String>()
        val invalid = mutableListOf<String>()

        /** The mapping of [value] at [path], with only what the index lacks. */
        fun properties(path: String, value: QueryValueSchema): ObjectNode {
            val properties = nodes.objectNode()
            objectProperties(
                value
            )?.forEach { (name, child) -> field("$path.$name", child)?.let { properties.set(name, it) } }
            return nodes.objectNode().also { it.set("properties", properties) }
        }

        /** The mapping of the field [path] of [value], or null when the index has it all or it maps nothing. */
        private fun field(path: String, value: QueryValueSchema): ObjectNode? {
            val single = value.mappable(mergesVariants) ?: return null
            return when {
                single.kind == QueryValueKind.OBJECT && single.additionalProperties != null -> leaf(
                    path,
                    FLATTENED,
                    flattened()
                )
                single.kind == QueryValueKind.OBJECT -> container(path, Property.Kind.Object, single)
                single.kind == QueryValueKind.ARRAY -> {
                    val items = checkNotNull(single.items).mappable(mergesVariants) ?: return null
                    if (items.kind == QueryValueKind.OBJECT && items.additionalProperties == null) {
                        container(path, Property.Kind.Nested, items)
                    } else {
                        field(path, items)
                    }
                }
                single.kind == QueryValueKind.SCALAR -> scalar(path, single)
                else -> null
            }
        }

        private fun container(path: String, kind: Property.Kind, value: QueryValueSchema): ObjectNode? {
            val mapped = existing.fields[path]
            if (mapped != null && mapped.kind != kind) invalid += path
            val children = properties(path, value)
            val hasChildren = !children.path("properties").isEmpty
            return when {
                mapped != null && !hasChildren -> null
                mapped != null -> children.also { if (mapped.kind == Property.Kind.Nested) it.put("type", "nested") }
                else -> {
                    if (kind == Property.Kind.Nested) created += path
                    children.also { if (kind == Property.Kind.Nested) it.put("type", "nested") }
                        .takeIf { hasChildren || kind == Property.Kind.Nested }
                }
            }
        }

        private fun scalar(path: String, value: QueryValueSchema): ObjectNode? {
            val types = value.valueTypes
            return when {
                value.semanticType == Temporal.Date && types == setOf(QueryValueType.STRING) -> leaf(
                    path,
                    Property.Kind.Date,
                    date()
                )
                types == setOf(QueryValueType.STRING) -> keywordLeaf(path, value.text)
                types == setOf(QueryValueType.INTEGER) -> leaf(path, Property.Kind.Long, typed("long"))
                types == setOf(QueryValueType.DECIMAL) || types == NUMBERS -> leaf(
                    path,
                    Property.Kind.Double,
                    typed("double")
                )
                types == setOf(QueryValueType.BOOLEAN) -> leaf(path, Property.Kind.Boolean, typed("boolean"))
                else -> null
            }
        }

        private fun leaf(path: String, kind: Property.Kind, mapping: ObjectNode): ObjectNode? {
            val mapped = existing.fields[path]
            if (mapped == null) {
                created += path
                return mapping
            }
            if (mapped.kind != kind) invalid += path
            return null
        }

        /**
         * A string at [path]: a `keyword`, with its `text` field when [text]. An index that maps it as a `keyword`
         * without the `text` field gets the field added, its `ignore_above` restated as found; one that maps the `text`
         * field as anything but `text` reports it.
         */
        private fun keywordLeaf(path: String, text: Boolean): ObjectNode? {
            val mapped = existing.fields[path] ?: return keyword(text, ElasticsearchSnapshotIndex.KEYWORD_LIMIT).also {
                created += path
            }
            if (mapped.kind != Property.Kind.Keyword) {
                invalid += path
                return null
            }
            if (!text) return null
            val textPath = "$path.$TEXT_FIELD"
            val mappedText = existing.fields[textPath] ?: return keyword(true, mapped.ignoreAbove).also {
                created += textPath
            }
            if (mappedText.kind != Property.Kind.Text) invalid += textPath
            return null
        }

        private fun objectProperties(value: QueryValueSchema): Map<String, QueryValueSchema>? =
            value.mappable(mergesVariants)?.takeIf { it.kind == QueryValueKind.OBJECT }?.properties
    }

    internal companion object {
        /** How many documents a second a rewrite indexes again by default (`reindex-requests-per-second`). */
        const val DEFAULT_REWRITE_RATE = 1000

        private val log = KotlinLogging.logger {}
        private val nodes: JsonNodeFactory = JsonNodeFactory.instance
        private val NUMBERS = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL)
        private val FLATTENED: Property.Kind = Property.Kind.Flattened

        private fun typed(type: String): ObjectNode = nodes.objectNode().put("type", type)

        /** The `text` field of a string marked as prose; full-text searches bind it by this name. */
        const val TEXT_FIELD = "text"

        /** A `keyword` capped at [ignoreAbove] when it is set, with a `text` field when [text]. */
        private fun keyword(text: Boolean, ignoreAbove: Int?): ObjectNode = typed("keyword").also { keyword ->
            ignoreAbove?.let { keyword.put("ignore_above", it) }
            if (text) keyword.putObject("fields").set(TEXT_FIELD, typed("text"))
        }

        private fun date(): ObjectNode = typed("date").put("format", "strict_date_optional_time||epoch_millis")

        private fun flattened(): ObjectNode = typed(
            "flattened"
        ).put("ignore_above", ElasticsearchSnapshotIndex.KEYWORD_LIMIT)
    }
}

/**
 * The value one field maps: the one non-null alternative of this value; with [merge], the alternatives of the variants
 * of a union taken together when they map alike (objects as one object of all their properties, scalars of one type as
 * that scalar, arrays as an array of their items); null when they do not, or there is none.
 */
internal fun QueryValueSchema.mappable(merge: Boolean): QueryValueSchema? {
    val branches = alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
    branches.singleOrNull()?.let { return it }
    if (!merge || branches.isEmpty()) return null
    val first = branches.first()
    return when {
        branches.all { it.kind == QueryValueKind.OBJECT && it.additionalProperties == null } ->
            QueryValueSchema(
                QueryValueKind.OBJECT,
                properties = branches.flatMap { it.properties.keys }.distinct().associateWith { name ->
                    unionOf(branches.mapNotNull { it.properties[name] })
                },
            )
        // One field for every variant: prose only when every variant says so.
        branches.all {
            it.kind == QueryValueKind.SCALAR && it.valueTypes == first.valueTypes &&
                it.semanticType == first.semanticType
        } -> branches.firstOrNull { !it.text } ?: first
        branches.all { it.kind == QueryValueKind.ARRAY } ->
            QueryValueSchema(QueryValueKind.ARRAY, items = unionOf(branches.map { checkNotNull(it.items) }))
        else -> null
    }
}

private fun unionOf(values: List<QueryValueSchema>): QueryValueSchema =
    values.distinct().singleOrNull() ?: QueryValueSchema(QueryValueKind.UNION, alternatives = values.distinct())

/**
 * Where the payload whose fields [ElasticsearchStateMappings] maps lies in a document: its dotted [path], its logical
 * [value] in a model's schema, and the mapping request that adds [properties][mapping] to it.
 */
internal interface ElasticsearchPayload {
    val path: String

    /** Whether the variants of a union payload (the events' bodies) map together, each field once. */
    val mergesVariants: Boolean

    fun value(schema: LogicalQuerySchema): QueryValueSchema?

    fun mapping(properties: ObjectNode): ObjectNode

    /** A payload at the root of each document: a snapshot's `state`. */
    class Root(override val path: String) : ElasticsearchPayload {
        override val mergesVariants: Boolean get() = false

        override fun value(schema: LogicalQuerySchema): QueryValueSchema? = schema.root.properties[path]

        override fun mapping(properties: ObjectNode): ObjectNode = JsonNodeFactory.instance.objectNode().also { root ->
            root.putObject("properties").set(path, properties)
        }
    }

    /**
     * The bodies of a stream's events, `body` in each element of the nested `events`: one union of every event's body,
     * each field mapped once for all the events that declare it alike; a field two events declare
     * with different types is not mapped.
     */
    object EventBodies : ElasticsearchPayload {
        private val TEMPLATE = QueryPathTemplate(
            listOf(
                QueryPathSegment.Property(EventStreamFields.EVENTS),
                QueryPathSegment.Item,
                QueryPathSegment.Property(EventFields.BODY),
            ),
        )

        override val path: String = "${EventStreamFields.EVENTS}.${EventFields.BODY}"

        override val mergesVariants: Boolean get() = true

        override fun value(schema: LogicalQuerySchema): QueryValueSchema? = schema.values[TEMPLATE]

        override fun mapping(properties: ObjectNode): ObjectNode = JsonNodeFactory.instance.objectNode().also { root ->
            root.putObject("properties").putObject(EventStreamFields.EVENTS)
                .put("type", "nested")
                .putObject("properties").set(EventFields.BODY, properties)
        }
    }
}
