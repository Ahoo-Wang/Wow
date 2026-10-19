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
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

/**
 * The mapping of the fields of a payload whose index does not map it dynamically (a snapshot's `state`, an event's
 * `body`), from the payload's logical schema, the Manifest's: Elasticsearch indexes a field only once its index maps
 * it, so these are its query indexes (decision D151).
 *
 * Each value maps by its logical type: a string to a `keyword` (capped at [ElasticsearchSnapshotIndex.KEYWORD_LIMIT])
 * with a `text` field for full-text search, a date string to a `date`, an integer (an epoch included) to a `long`, a
 * decimal to a `double`, a boolean to a `boolean`, an object to its properties, an array of objects to a `nested`
 * field (so a predicate matches one element and an aggregation expands them), a map (an object of any keys) to a
 * `flattened` field, an array to its items' mapping. A value of several types, or of none known, is not mapped: it
 * stays in `_source`, unindexed.
 *
 * [manage] adds the fields an index lacks and reports those it maps otherwise (a field's type cannot change); once it
 * added some to an index that holds documents, it reindexes them in place (`_update_by_query` without a script, which
 * keeps each `_source` as it is), so they are found by the new fields too. A field the index maps already is left as
 * it is.
 */
internal class ElasticsearchStateMappings(
    private val client: ElasticsearchAsyncClient,
    private val mappingResolver: ElasticsearchIndexMappingResolver,
    /** The payload's field, at the root of each document. */
    private val payload: String,
) {
    /** The logical schemas each index was found to map already, so a load maps nothing twice. */
    private val managed = ConcurrentHashMap<String, LogicalQuerySchema>()

    /**
     * Maps the payload fields of [schema] that [index] lacks, when [create] (`auto`), else only reports them
     * (`validate`): the fields it created, and those it found missing or mapped otherwise.
     */
    suspend fun manage(index: String, schema: LogicalQuerySchema, create: Boolean = true): QueryIndexReport {
        if (managed[index] === schema) return QueryIndexReport.NONE
        val payloadValue = schema.root.properties[payload] ?: return QueryIndexReport.NONE
        val existing = mappingResolver.refresh(index)
        if (existing.provisional) return QueryIndexReport.NONE
        val plan = Plan(existing)
        val properties = plan.properties(payload, payloadValue)
        if (plan.created.isEmpty() || !create) {
            if (plan.invalid.isEmpty() && plan.created.isEmpty()) managed[index] = schema
            return QueryIndexReport(missing = plan.created, invalid = plan.invalid)
        }
        val mapping = nodes.objectNode().also { root ->
            root.putObject("properties").set(payload, properties)
        }
        client.indices().putMapping(
            PutMappingRequest.of { it.index(index).withJson(StringReader(WowJson.mapper.writeValueAsString(mapping))) },
        ).awaitElasticsearch()
        mappingResolver.evict(index)
        // The documents written before the fields were mapped are indexed again, in the background.
        client.updateByQuery {
            it.index(
                index
            ).conflicts(Conflicts.Proceed).waitForCompletion(false)
        }.awaitElasticsearch()
        managed[index] = schema
        return QueryIndexReport(created = plan.created, invalid = plan.invalid)
    }

    /** One walk of a payload's schema against an index's existing mapping. */
    private class Plan(private val existing: ElasticsearchIndexMapping) {
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
            val single = value.single() ?: return null
            return when {
                single.kind == QueryValueKind.OBJECT && single.additionalProperties != null -> leaf(
                    path,
                    FLATTENED,
                    flattened()
                )
                single.kind == QueryValueKind.OBJECT -> container(path, Property.Kind.Object, single)
                single.kind == QueryValueKind.ARRAY -> {
                    val items = checkNotNull(single.items).single() ?: return null
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
                types == setOf(QueryValueType.STRING) -> leaf(path, Property.Kind.Keyword, keyword())
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

        private fun objectProperties(value: QueryValueSchema): Map<String, QueryValueSchema>? =
            value.single()?.takeIf { it.kind == QueryValueKind.OBJECT }?.properties
    }

    private companion object {
        val nodes: JsonNodeFactory = JsonNodeFactory.instance
        val NUMBERS = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL)
        val FLATTENED: Property.Kind = Property.Kind.Flattened

        /** The one non-null alternative of this value, or null when it has several or none. */
        fun QueryValueSchema.single(): QueryValueSchema? =
            alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }.singleOrNull()

        fun typed(type: String): ObjectNode = nodes.objectNode().put("type", type)

        fun keyword(): ObjectNode = typed(
            "keyword"
        ).put("ignore_above", ElasticsearchSnapshotIndex.KEYWORD_LIMIT).also {
            it.putObject("fields").set("text", typed("text"))
        }

        fun date(): ObjectNode = typed("date").put("format", "strict_date_optional_time||epoch_millis")

        fun flattened(): ObjectNode = typed("flattened").put("ignore_above", ElasticsearchSnapshotIndex.KEYWORD_LIMIT)
    }
}
