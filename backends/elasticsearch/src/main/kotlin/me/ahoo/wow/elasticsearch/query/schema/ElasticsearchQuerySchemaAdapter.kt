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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.Property
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventIndex
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.elasticsearch.query.hasArrayBranch
import me.ahoo.wow.elasticsearch.query.isElementScope
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.spec.MetricSpec
import me.ahoo.wow.spi.json.query.EventStreamFields
import java.util.concurrent.ConcurrentHashMap

/**
 * The storage facts of one index (the Catalog's port): what its mapping proves about a logical model, per
 * path, read through [mappingResolver]. [beforeLoad] runs before each load, with the logical model: the snapshot
 * queries map the state fields the index lacks there ([me.ahoo.wow.elasticsearch.query.ElasticsearchStateMappings]).
 */
internal class ElasticsearchQuerySchemaAdapter(
    private val indexName: String,
    private val mappingResolver: ElasticsearchIndexMappingResolver,
    private val model: QueryModel = QueryModel.SNAPSHOT,
    private val beforeLoad: suspend (LogicalQuerySchema) -> Unit = {},
) : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        load(logicalSchema) { mappingResolver.currentOrLoad(indexName) }

    override suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        load(logicalSchema) { mappingResolver.refresh(indexName) }

    @Suppress("TooGenericExceptionCaught") // Any failure to read the mapping is the schema's unavailability.
    private suspend fun load(
        logicalSchema: LogicalQuerySchema,
        mapping: suspend () -> ElasticsearchIndexMapping,
    ): QueryStorageFacts = try {
        beforeLoad(logicalSchema)
        facts(logicalSchema, mapping(), model)
    } catch (error: QuerySchemaUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException(
            "Failed to resolve Elasticsearch query schema for index [$indexName].",
            error
        )
    }

    companion object {
        /**
         * The metrics a `terms` aggregation orders one group by, taking its first groups exactly, on one primary
         * shard: those that are never null, so their order matches the core's (a SUM of no values is `null` in Wow and
         * 0 here).
         */
        private val EXACT_TERMS_TOP_N = setOf(MetricSpec.COUNT, MetricSpec.DISTINCT_COUNT)

        /**
         * Composite aggregations have no bucket selector, no metric ordering and no empty buckets: the core computes
         * HAVING, top-N by a metric and dense fill over the groups this backend streams. On one primary shard a
         * single TERMS group's top N by a count is a `terms` aggregation instead ([EXACT_TERMS_TOP_N]).
         */
        internal val STORAGE_SUPPORT = storageSupport()

        /** [STORAGE_SUPPORT] with an offset window of [maxOffsetWindow] and a native top-N for [termsTopN]. */
        private fun storageSupport(maxOffsetWindow: Int? = null, termsTopN: Set<MetricSpec> = emptySet()) =
            StorageSupport(
                paging = PagingSupport(maxOffsetWindow = maxOffsetWindow),
                aggregation = AggregationSupport(
                    having = SupportMode.RESIDUAL,
                    topN = SupportMode.RESIDUAL,
                    denseFill = SupportMode.RESIDUAL,
                    termsTopN = termsTopN,
                ),
                // No value is indexed for null or [], and keyword doc values are sorted and deduplicated.
                absentValues = AbsentValues.AS_MISSING,
                arrayEquality = SupportMode.NONE,
                // A null element is not indexed either, so an array holding one cannot sort with it the least.
                nullElementSort = SupportMode.NONE,
            )

        /**
         * What the index mapping proves about [logicalSchema]: per path, the capabilities its field kinds and options
         * can execute and the (multi-)field each binds to (the Catalog applies the storage-independent rules), and
         * model-wide search.
         */
        internal fun facts(
            logicalSchema: LogicalQuerySchema,
            mapping: ElasticsearchIndexMapping,
            model: QueryModel = QueryModel.SNAPSHOT,
        ): QueryStorageFacts {
            val nestedPaths = mapping.fields.filterValues { it.kind == Property.Kind.Nested }.keys
            val paths = mapping.logicalBindingPaths(logicalSchema)
            val arrayPaths = paths.filter { path ->
                logicalSchema.value(path)?.hasArrayBranch() == true &&
                    path.segments.none { it is QueryPathSegment.Key }
            }.mapTo(linkedSetOf()) { it.field(emptyList()).path }
            // A nested mapping whose logical value is no element scope cannot be read element by element.
            val invalidNested = nestedPaths.filterTo(linkedSetOf()) { path ->
                logicalSchema.value(path.template())?.isElementScope() != true
            }
            mapping.warnTruncatedDecimals(logicalSchema, paths)
            val bindings = paths.associateWith { path ->
                val value = checkNotNull(logicalSchema.value(path))
                val symbolic = path.segments.any { it is QueryPathSegment.Key }
                val source = if (symbolic) null else path.field(emptyList()).path
                val projection = when {
                    source in METADATA_FIELDS -> null
                    source != null && source in mapping.fields -> mapping.fields.getValue(
                        source
                    ).projectionPath?.let { target ->
                        if (target == source) path else target.sourceTemplate(arrayPaths + nestedPaths)
                    }
                    else -> path
                }?.takeIf { mapping.sourceAvailable(it) }
                QueryValueBindings(
                    bindings = mapping.valueBindings(
                        model,
                        path,
                        source,
                        value,
                        invalidNested,
                        nestedPaths,
                        arrayPaths,
                    ),
                    projectionPath = projection,
                    responsePath = projection,
                )
            }
            // A search that names no field searches the record's fields searched by words (outside every element):
            // one `multi_match` over them, so a phrase only when each of them takes one.
            val recordSearch = bindings.filterKeys { it.atRecord() }.values.map { it.bindings }
                .filter { QueryCapability.FULL_TEXT_TERMS in it }
            return QueryStorageFacts(
                fullProjectionAvailable = mapping.fullProjectionAvailable,
                // `cardinality` (HyperLogLog++) and `percentiles` (TDigest) are estimates.
                approximateMetrics = setOf("DISTINCT_COUNT", "PERCENTILE"),
                // A search whose `from + size` passes the index's window fails; admission rejects the page instead.
                storage = mapping.storageSupport(),
                provisional = mapping.provisional,
                capabilities = buildSet {
                    if (recordSearch.isNotEmpty()) add(QueryCapability.FULL_TEXT_TERMS)
                    if (recordSearch.isNotEmpty() && recordSearch.all { QueryCapability.FULL_TEXT_PHRASE in it }) {
                        add(QueryCapability.FULL_TEXT_PHRASE)
                    }
                },
                bindings = bindings,
            )
        }

        /** [STORAGE_SUPPORT] over this index: its offset window, and a native top-N where it has one shard. */
        private fun ElasticsearchIndexMapping.storageSupport(): StorageSupport =
            storageSupport(maxResultWindow, if (singleShard) EXACT_TERMS_TOP_N else emptySet())

        @Suppress("LongParameterList")
        private fun ElasticsearchIndexMapping.valueBindings(
            model: QueryModel,
            path: QueryPathTemplate,
            source: String?,
            value: QueryValueSchema,
            invalidNested: Set<String>,
            nestedPaths: Set<String>,
            arrayPaths: Set<String>,
        ): Map<QueryCapability, QueryFieldBindingTemplate> = when {
            provisional && unmapped(source) -> provisionalBindings(path, value)
            source == null -> flattenedKeyBindings(path, value)
            else -> BUILT_IN_CAPABILITIES.mapNotNull { capability ->
                (
                    documentIdBinding(model, source, capability)
                        ?: presenceMarkerBinding(model, source, capability)
                        ?: binding(source, value, capability, invalidNested, nestedPaths, arrayPaths)
                    )?.let { capability to it }
            }.toMap()
        }.filterKeys { it !in FULL_TEXT_CAPABILITIES || value.searchedByWords() }

        private val log = KotlinLogging.logger {}

        /** The `index:path` pairs already warned of: the schema is revalidated periodically. */
        private val truncatedDecimalWarnings = ConcurrentHashMap.newKeySet<String>()

        /**
         * Warns once of each decimal path the index maps as an integral field (dynamic mapping makes one `long` when
         * the first value it sees is integral): Elasticsearch truncates the decimals it indexes there, so the path
         * offers no filter, sort or aggregate (see [storageKinds]).
         */
        private fun ElasticsearchIndexMapping.warnTruncatedDecimals(
            logicalSchema: LogicalQuerySchema,
            paths: Collection<QueryPathTemplate>,
        ) {
            if (provisional) return
            paths.forEach { path ->
                if (path.segments.any { it is QueryPathSegment.Key }) return@forEach
                val decimal = logicalSchema.value(path)?.scalarValueTypes()?.contains(QueryValueType.DECIMAL) == true
                if (!decimal) return@forEach
                val source = path.field(emptyList()).path
                val mapped = fields[source] ?: return@forEach
                if (mapped.kind !in INTEGER_KINDS || !truncatedDecimalWarnings.add("$indexName:$source")) return@forEach
                log.warn {
                    "Elasticsearch index [$indexName] maps the decimal [$source] as [${mapped.kind.jsonValue()}], " +
                        "which truncates the decimals it indexes: it is not filtered, sorted or aggregated. " +
                        "Map it as scaled_float or double in the index definition."
                }
            }
        }

        private val METADATA_FIELDS = setOf(DOCUMENT_ID, "_score", "_doc", "_shard_doc")

        /** A path the mapping holds no field for: no metadata field, and no field at or below a flattened one. */
        private fun ElasticsearchIndexMapping.unmapped(source: String?): Boolean =
            source == null || source !in METADATA_FIELDS && find(source) == null

        /**
         * Before the first write, a path the templates do not map yet (one that dynamic mapping will add, or a map key)
         * holds no value: every query of the missing index answers nothing. It is bound for every capability its
         * logical value allows in some field kind, at its own path, so a query of it answers nothing rather than being
         * refused; the first load after the write binds what the index then maps.
         */
        private fun provisionalBindings(
            path: QueryPathTemplate,
            value: QueryValueSchema,
        ): Map<QueryCapability, QueryFieldBindingTemplate> {
            val physical = QueryFieldBindingTemplate(
                QueryPathTemplate(path.segments.filter { it != QueryPathSegment.Item }),
                null,
            )
            return BUILT_IN_CAPABILITIES.filter { capability ->
                when (capability) {
                    QueryCapability.PRESENCE -> true
                    QueryCapability.ELEMENT_SCOPE -> value.isElementScope()
                    else -> value.provesSomeKind(capability)
                }
            }.associateWith { physical }
        }

        /**
         * A map key below a `flattened` field: the mapping never lists its
         * keys, so the path is bound with its key, as the field indexes each key's values, for what a flattened key
         * executes: presence and exact string matches.
         */
        private fun ElasticsearchIndexMapping.flattenedKeyBindings(
            path: QueryPathTemplate,
            value: QueryValueSchema,
        ): Map<QueryCapability, QueryFieldBindingTemplate> {
            val prefix = path.segments.takeWhile { it is QueryPathSegment.Property }
                .joinToString(".") { (it as QueryPathSegment.Property).name }
            val mapped = fields[prefix]?.takeIf { it.kind == Property.Kind.Flattened } ?: return emptyMap()
            val physical = QueryFieldBindingTemplate(
                QueryPathTemplate(path.segments.filter { it != QueryPathSegment.Item }),
                setOf(QueryStorageType(mapped.kind.jsonValue())),
            )
            // A key of several values (a tag's) indexes each one as the key's value.
            val element = value.items?.takeIf { value.kind == QueryValueKind.ARRAY } ?: value
            return FLATTENED_KEY_CAPABILITIES.filter { mapped.supports(it, element, flattenedDescendant = true) }
                .associateWith { physical }
        }

        private val FLATTENED_KEY_CAPABILITIES = listOf(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)

        private fun ElasticsearchIndexMapping.sourceAvailable(path: QueryPathTemplate): Boolean =
            if (path.segments.any { it is QueryPathSegment.Key }) {
                fullProjectionAvailable
            } else {
                sourceAvailable(path.field(emptyList()).path)
            }

        /** Specialize only observed keys, preserving the declaration's named overrides. */
        private fun ElasticsearchIndexMapping.logicalBindingPaths(
            logicalSchema: LogicalQuerySchema
        ): Set<QueryPathTemplate> =
            buildSet {
                addAll(logicalSchema.values.keys.filter { it.segments.isNotEmpty() })
                logicalSchema.values.keys.filter { it.segments.any { part -> part is QueryPathSegment.Key } }
                    .forEach { template ->
                        fields.keys.mapNotNullTo(this) { template.withMappedKeys(it) }
                    }
            }

        private fun QueryPathTemplate.withMappedKeys(mappedPath: String): QueryPathTemplate? {
            val parts = mappedPath.split('.')
            val named = segments.filter { it != QueryPathSegment.Item }
            if (parts.size != named.size) return null
            if (named.zip(parts).any { (segment, part) ->
                    segment is QueryPathSegment.Property && segment.name != part
                }
            ) {
                return null
            }
            var index = 0
            return QueryPathTemplate(
                segments.map { segment ->
                    if (segment == QueryPathSegment.Item) segment else QueryPathSegment.Property(parts[index++])
                }
            )
        }

        private val BUILT_IN_CAPABILITIES = listOf(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.FULL_TEXT_TERMS,
            QueryCapability.FULL_TEXT_PHRASE,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.ELEMENT_SCOPE,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
            QueryCapability.AGGREGATE_TEMPORAL,
        )

        private fun ElasticsearchIndexMapping.binding(
            source: String,
            logical: QueryValueSchema,
            capability: QueryCapability,
            invalidNestedParents: Set<String>,
            nestedPaths: Set<String>,
            arrayPaths: Set<String>,
        ): QueryFieldBindingTemplate? {
            if (invalidNestedParents.any { source.startsWith("$it.") }) return null
            metadataBinding(source, logical, capability)?.let { return it }
            val mapped = find(source) ?: return null
            val selected = if (mapped.supports(
                    capability,
                    logical,
                    source !in fields && mapped.kind == Property.Kind.Flattened
                )
            ) {
                (if (capability == QueryCapability.CURSOR_SORT) mapped.physicalPath else source) to mapped
            } else {
                mapped.selectMultiField(this, capability, logical) ?: return null
            }
            if (capability == QueryCapability.CURSOR_SORT && !selected.first.canCursorSort(arrayPaths, nestedPaths)) {
                return null
            }
            return QueryFieldBindingTemplate(
                physicalPath = selected.first.template(),
                storageTypes = setOf(QueryStorageType(selected.second.kind.jsonValue())),
                // The innermost nested mapping the field lies in: a sort addresses the field through it.
                physicalScope = nestedPaths.filter { selected.first.startsWith("$it.") }.maxByOrNull { it.length }
                    ?.let(::QueryField),
            )
        }

        private fun metadataBinding(
            source: String,
            logical: QueryValueSchema,
            capability: QueryCapability,
        ): QueryFieldBindingTemplate? {
            val expected = when (source) {
                DOCUMENT_ID -> QueryValueType.STRING to QueryCapability.EXACT_MATCH
                "_score" -> QueryValueType.DECIMAL to QueryCapability.SORT
                "_doc", "_shard_doc" -> QueryValueType.INTEGER to QueryCapability.SORT
                else -> return null
            }
            if (logical.valueTypes != setOf(expected.first) || capability != expected.second) return null
            return QueryFieldBindingTemplate(source.template(), null)
        }

        /**
         * An exact match on the field a built-in store writes as the document `_id` addresses `_id` itself, so
         * identity filters compile to an `ids` query. `_id` has no doc values, so sorting and aggregating keep the
         * indexed field.
         */
        private fun documentIdBinding(
            model: QueryModel,
            source: String,
            capability: QueryCapability,
        ): QueryFieldBindingTemplate? {
            if (capability != QueryCapability.EXACT_MATCH || DOCUMENT_ID_SOURCES[model] != source) return null
            return QueryFieldBindingTemplate(DOCUMENT_ID.template(), null)
        }

        private const val DOCUMENT_ID = "_id"

        /**
         * The presence of a field a built-in store marks with a field of its own: an events index cannot tell an
         * `access` object of null values from a missing one, so whether a stream is stamped is its `accessVersion`.
         */
        private fun ElasticsearchIndexMapping.presenceMarkerBinding(
            model: QueryModel,
            source: String,
            capability: QueryCapability,
        ): QueryFieldBindingTemplate? {
            if (capability != QueryCapability.PRESENCE) return null
            val marker = PRESENCE_MARKERS[model to source] ?: return null
            val mapped = fields[marker] ?: return null
            return QueryFieldBindingTemplate(marker.template(), setOf(QueryStorageType(mapped.kind.jsonValue())))
        }

        private val PRESENCE_MARKERS: Map<Pair<QueryModel, String>, String> = mapOf(
            (QueryModel.EVENTS to EventStreamFields.ACCESS) to ElasticsearchEventIndex.ACCESS_VERSION,
        )

        /**
         * The field each built-in store writes as the document `_id`: none. A snapshot's `_id` is its tenant and
         * aggregate id together, an event stream's its ref and version, which no queryable field holds alone.
         */
        private val DOCUMENT_ID_SOURCES: Map<QueryModel, String> = emptyMap()

        /**
         * Whether the physical field a cursor sorts by holds one value per document: not inside an array or a nested
         * mapping. The Catalog rules out logical arrays; a physical field can lie elsewhere than its logical source.
         */
        private fun String.canCursorSort(arrayPaths: Set<String>, nestedPaths: Set<String>): Boolean =
            arrayPaths.none { atOrBelow(it) } && nestedPaths.none { atOrBelow(it) }

        private fun ElasticsearchMappedField.selectMultiField(
            mapping: ElasticsearchIndexMapping,
            capability: QueryCapability,
            logical: QueryValueSchema,
        ): Pair<String, ElasticsearchMappedField>? {
            val supported = multiFields.mapNotNull { path ->
                mapping.fields[path]?.takeIf { it.supports(capability, logical) }?.let { path to it }
            }
            preferredSuffixes(capability).forEach { suffix ->
                supported.singleOrNull { (path) -> path.substringAfterLast('.') == suffix }?.let { return it }
            }
            return supported.singleOrNull()
        }

        private fun preferredSuffixes(capability: QueryCapability): List<String> = when (capability) {
            QueryCapability.FULL_TEXT_TERMS,
            QueryCapability.FULL_TEXT_PHRASE,
            -> listOf("text")
            else -> listOf("keyword", "exact")
        }
    }
}

/** Whether this path is a field of the record itself: no map key, and no field of an array's elements. */
private fun QueryPathTemplate.atRecord(): Boolean {
    if (segments.any { it is QueryPathSegment.Key }) return false
    val firstItem = segments.indexOfFirst { it == QueryPathSegment.Item }
    return firstItem < 0 || segments.drop(firstItem).none { it is QueryPathSegment.Property }
}

private fun String.atOrBelow(parent: String): Boolean = this == parent || startsWith("$parent.")

private fun String.template(): QueryPathTemplate =
    QueryPathTemplate(split('.').map(QueryPathSegment::Property))

private fun String.sourceTemplate(arrayPaths: Set<String>): QueryPathTemplate {
    val parts = split('.')
    return QueryPathTemplate(
        buildList {
            parts.forEachIndexed { index, part ->
                add(QueryPathSegment.Property(part))
                if (index < parts.lastIndex && parts.take(index + 1).joinToString(".") in arrayPaths) {
                    add(QueryPathSegment.Item)
                }
            }
        }
    )
}
