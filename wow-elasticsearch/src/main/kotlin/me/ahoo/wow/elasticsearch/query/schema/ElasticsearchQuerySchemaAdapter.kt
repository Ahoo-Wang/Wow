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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.operationValues
import reactor.core.publisher.Mono

class ElasticsearchQuerySchemaAdapter(
    private val indexName: String,
    private val mappingResolver: ElasticsearchIndexMappingResolver,
    private val model: QueryModel = QueryModel.SNAPSHOT,
) : QuerySchemaBackendAdapter {
    override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> =
        load(logicalSchema, mappingResolver.currentOrLoad(indexName))

    override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> =
        load(logicalSchema, mappingResolver.refresh(indexName))

    private fun load(
        logicalSchema: LogicalQuerySchema,
        mapping: Mono<ElasticsearchIndexMapping>,
    ): Mono<QueryModelSchema> = mapping.map { bind(logicalSchema, it, model) }
        .onErrorMap { error ->
            if (error is QuerySchemaUnavailableException) {
                error
            } else {
                QuerySchemaUnavailableException(
                    "Failed to resolve Elasticsearch query schema for index [$indexName].",
                    error,
                )
            }
        }

    companion object {
        internal fun bind(
            logicalSchema: LogicalQuerySchema,
            mapping: ElasticsearchIndexMapping,
        ): QueryModelSchema = bind(logicalSchema, mapping, QueryModel.SNAPSHOT)

        internal fun bind(
            logicalSchema: LogicalQuerySchema,
            mapping: ElasticsearchIndexMapping,
            model: QueryModel,
        ): QueryModelSchema {
            val nestedPaths = mapping.fields.filterValues { it.kind == Property.Kind.Nested }.keys
            val paths = mapping.logicalBindingPaths(logicalSchema)
            val arrayPaths = paths.filter { path ->
                logicalSchema.value(path)?.hasArrayBranch() == true &&
                    path.segments.none { it is QueryPathSegment.Key }
            }.mapTo(linkedSetOf()) { it.field(emptyList()).path }
            val invalidNested = nestedPaths.filterTo(linkedSetOf()) { path ->
                logicalSchema.value(path.template())?.isElementScope != true
            }
            val rootSearchFields = mapping.fields.filterKeys { path ->
                nestedPaths.none { path.startsWith("$it.") }
            }.values
            return QueryModelSchema(
                model = model,
                definition = logicalSchema,
                fullProjectionAvailable = mapping.fullProjectionAvailable,
                capabilities = buildSet {
                    if (rootSearchFields.any(ElasticsearchMappedField::supportsModelFullText)) {
                        add(QueryCapability.FULL_TEXT_TERMS)
                    }
                    if (rootSearchFields.any(ElasticsearchMappedField::supportsModelPhraseSearch)) {
                        add(QueryCapability.FULL_TEXT_PHRASE)
                    }
                },
                bindings = paths.associateWith { path ->
                    val value = checkNotNull(logicalSchema.value(path))
                    val symbolic = path.segments.any { it is QueryPathSegment.Key }
                    val source = if (symbolic) null else path.field(emptyList()).path
                    val projection = when {
                        source in setOf("_id", "_score", "_doc", "_shard_doc") -> null
                        source != null && source in mapping.fields -> mapping.fields.getValue(
                            source
                        ).projectionPath?.let { target ->
                            if (target == source) path else target.sourceTemplate(arrayPaths + nestedPaths)
                        }
                        else -> path
                    }?.takeIf { mapping.sourceAvailable(it) }
                    QueryValueBindings(
                        bindings = if (source == null) {
                            emptyMap()
                        } else {
                            BUILT_IN_CAPABILITIES.mapNotNull { capability ->
                                mapping.binding(source, value, capability, invalidNested, nestedPaths, arrayPaths)
                                    ?.let { capability to it }
                            }.toMap()
                        },
                        projectionPath = projection,
                        responsePath = projection,
                    )
                },
            )
        }

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
            if (capability == QueryCapability.CURSOR_SORT && !logical.canCursorSort(source, selected.first, arrayPaths, nestedPaths)) {
                return null
            }
            return QueryFieldBindingTemplate(
                physicalPath = selected.first.template(),
                storageTypes = setOf(QueryStorageType(selected.second.kind.jsonValue())),
            )
        }

        private fun metadataBinding(
            source: String,
            logical: QueryValueSchema,
            capability: QueryCapability,
        ): QueryFieldBindingTemplate? {
            val expected = when (source) {
                "_id" -> QueryValueType.STRING to QueryCapability.EXACT_MATCH
                "_score" -> QueryValueType.DECIMAL to QueryCapability.SORT
                "_doc", "_shard_doc" -> QueryValueType.INTEGER to QueryCapability.SORT
                else -> return null
            }
            if (logical.valueTypes != setOf(expected.first) || capability != expected.second) return null
            return QueryFieldBindingTemplate(source.template(), null)
        }

        private fun QueryValueSchema.canCursorSort(
            source: String,
            physical: String,
            arrayPaths: Set<String>,
            nestedPaths: Set<String>,
        ): Boolean {
            if (hasArrayBranch()) return false
            if (arrayPaths.any { source.atOrBelow(it) || physical.atOrBelow(it) }) return false
            return nestedPaths.none { physical.atOrBelow(it) }
        }

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

@Suppress("CyclomaticComplexMethod") // One exhaustive table of native capability requirements.
private fun ElasticsearchMappedField.supports(
    capability: QueryCapability,
    logical: QueryValueSchema,
    flattenedDescendant: Boolean = false,
): Boolean {
    if (!enabled || nullValue != null || !logical.provesIndexedValues(ignoreAbove)) return false
    if (normalizer != null && capability in NORMALIZED_VALUE_CAPABILITIES) return false
    val executable = when (capability) {
        QueryCapability.PRESENCE -> queryable
        QueryCapability.EXACT_MATCH -> queryable && kind in EXACT_KINDS
        QueryCapability.LITERAL_MATCH -> indexed && kind in LITERAL_KINDS
        QueryCapability.RANGE -> queryable && kind in RANGE_KINDS
        QueryCapability.FULL_TEXT_TERMS -> indexed && kind in SEARCH_KINDS
        QueryCapability.FULL_TEXT_PHRASE -> indexed && ignoreAbove == null && nullValue == null && kind in PHRASE_SEARCH_KINDS
        QueryCapability.SORT -> sortable && (kind in EXACT_KINDS || (indexed && kind == Property.Kind.Text))
        QueryCapability.CURSOR_SORT -> {
            sortable && (kind in CURSOR_SORT_KINDS || (indexed && kind == Property.Kind.Text))
        }
        QueryCapability.ELEMENT_SCOPE -> kind == Property.Kind.Nested
        QueryCapability.AGGREGATE_TERMS -> aggregatable && (kind in EXACT_KINDS || kind == Property.Kind.Text)
        QueryCapability.AGGREGATE_NUMERIC -> aggregatable && kind in NUMERIC_KINDS
        QueryCapability.AGGREGATE_TEMPORAL -> aggregatable && (kind in NUMERIC_KINDS || kind in DATE_KINDS)

        else -> false
    }
    return executable && (
        capability == QueryCapability.PRESENCE ||
            logical.proves(capability, kind) ||
            flattenedDescendant && capability == QueryCapability.EXACT_MATCH && logical.branches().all {
                it.kind == QueryValueKind.SCALAR && it.valueTypes == setOf(QueryValueType.STRING)
            }
        )
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

private fun QueryValueSchema.branches(): List<QueryValueSchema> =
    if (kind == QueryValueKind.UNION) alternatives.flatMap { it.branches() } else listOf(this)

private fun QueryValueSchema.hasArrayBranch(): Boolean = branches().any { it.kind == QueryValueKind.ARRAY }

private val QueryValueSchema.isElementScope: Boolean
    get() = branches().filter { it.kind != QueryValueKind.NULL }.let { branches ->
        branches.isNotEmpty() && branches.all {
            it.kind == QueryValueKind.ARRAY &&
                checkNotNull(it.items).branches().filter { item -> item.kind != QueryValueKind.NULL }.let { items ->
                    items.isNotEmpty() && items.all { item -> item.kind == QueryValueKind.OBJECT }
                }
        }
    }

/** A length-limited index can represent a logical domain only when every declared value fits. */
private fun QueryValueSchema.provesIndexedValues(ignoreAbove: Int?): Boolean {
    if (ignoreAbove == null) return true
    return operationValues().all { value ->
        if (value.kind == QueryValueKind.NULL) return@all true
        if (value.kind != QueryValueKind.SCALAR || value.valueTypes != setOf(QueryValueType.STRING)) return@all false
        val values = value.enumValues ?: return@all false
        values.isNotEmpty() && values.all { it.isNull || it.isString && it.asString().length <= ignoreAbove }
    }
}

private fun QueryValueSchema.proves(capability: QueryCapability, kind: Property.Kind): Boolean {
    if (capability == QueryCapability.ELEMENT_SCOPE) return isElementScope && kind in NESTED_KINDS
    val values = branches().filter { it.kind != QueryValueKind.NULL }.flatMap {
        if (it.kind == QueryValueKind.ARRAY) checkNotNull(it.items).branches() else listOf(it)
    }.filter { it.kind != QueryValueKind.NULL }
    if (capability == QueryCapability.AGGREGATE_TEMPORAL && values.map {
            it.semanticType
        }.distinct().size != 1
    ) {
        return false
    }
    return values.isNotEmpty() && values.all { value ->
        value.kind == QueryValueKind.SCALAR && value.storageRequirements(capability).let { requirements ->
            requirements.isNotEmpty() && requirements.all { kind in it }
        }
    }
}

private fun QueryValueSchema.storageRequirements(
    capability: QueryCapability,
): List<Set<Property.Kind>> = when (capability) {
    QueryCapability.EXACT_MATCH -> valueRequirements()
    QueryCapability.LITERAL_MATCH,
    QueryCapability.FULL_TEXT_TERMS,
    QueryCapability.FULL_TEXT_PHRASE,
    -> stringRequirements()
    QueryCapability.RANGE -> rangeRequirements()
    QueryCapability.SORT,
    QueryCapability.CURSOR_SORT,
    QueryCapability.AGGREGATE_TERMS,
    -> valueRequirements()
    QueryCapability.ELEMENT_SCOPE -> if (isElementScope) {
        listOf(NESTED_KINDS)
    } else {
        emptyList()
    }
    QueryCapability.AGGREGATE_NUMERIC -> numericRequirements()
    QueryCapability.AGGREGATE_TEMPORAL -> temporalRequirements()
    else -> emptyList()
}

private fun QueryValueSchema.valueRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date,
    is Temporal.Epoch,
    -> temporalRequirements()
    else -> valueTypes.map(QueryValueType::storageKinds)
}

private fun QueryValueSchema.stringRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date,
    is Temporal.Epoch,
    -> emptyList()
    else -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(STRING_KINDS) else emptyList()
}

private fun QueryValueSchema.numericRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date -> emptyList()
    is Temporal.Epoch -> temporalRequirements()
    else -> if (valueTypes.all { it == QueryValueType.INTEGER || it == QueryValueType.DECIMAL }) {
        valueTypes.map { if (it == QueryValueType.INTEGER) INTEGER_KINDS else NUMERIC_KINDS }
    } else {
        emptyList()
    }
}

private fun QueryValueSchema.rangeRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    is Temporal.Formatted -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(KEYWORD_KINDS) else emptyList()
    else -> temporalRequirements().ifEmpty { numericRequirements().ifEmpty { stringRequirements() } }
}

private fun QueryValueSchema.temporalRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(DATE_KINDS) else emptyList()
    is Temporal.Epoch -> if (
        valueTypes == setOf(QueryValueType.INTEGER)
    ) {
        listOf(SIGNED_INTEGER_KINDS)
    } else {
        emptyList()
    }
    else -> emptyList()
}

private fun QueryValueType.storageKinds(): Set<Property.Kind> = when (this) {
    QueryValueType.STRING -> STRING_KINDS
    QueryValueType.INTEGER -> INTEGER_KINDS
    QueryValueType.DECIMAL -> NUMERIC_KINDS
    QueryValueType.BOOLEAN -> BOOLEAN_KINDS
    else -> emptySet()
}

private val ElasticsearchMappedField.queryable: Boolean
    get() = indexed || (sortable && kind in DOC_VALUE_QUERY_KINDS)

private fun ElasticsearchMappedField.supportsModelFullText(): Boolean {
    if (ignoreAbove != null || nullValue != null) return false
    return indexed && kind in MATCH_KINDS
}

private fun ElasticsearchMappedField.supportsModelPhraseSearch(): Boolean {
    if (ignoreAbove != null || nullValue != null) return false
    return indexed && kind in PHRASE_SEARCH_KINDS
}

private val SIGNED_INTEGER_KINDS = setOf(
    Property.Kind.Byte,
    Property.Kind.Short,
    Property.Kind.Integer,
    Property.Kind.Long,
)

private val INTEGER_KINDS = SIGNED_INTEGER_KINDS + setOf(
    Property.Kind.TokenCount,
    Property.Kind.UnsignedLong,
)

private val NUMERIC_KINDS = INTEGER_KINDS + setOf(
    Property.Kind.HalfFloat,
    Property.Kind.Float,
    Property.Kind.Double,
    Property.Kind.ScaledFloat,
)

private val KEYWORD_KINDS = setOf(
    Property.Kind.Keyword,
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    Property.Kind.IcuCollationKeyword,
)

private val TERM_KINDS = KEYWORD_KINDS + Property.Kind.Wildcard

private val BOOLEAN_KINDS = setOf(Property.Kind.Boolean)

private val DATE_KINDS = setOf(Property.Kind.Date, Property.Kind.DateNanos)

private val NESTED_KINDS = setOf(Property.Kind.Nested)

private val RANGE_FIELD_KINDS = setOf(
    Property.Kind.IntegerRange,
    Property.Kind.FloatRange,
    Property.Kind.LongRange,
    Property.Kind.DoubleRange,
    Property.Kind.DateRange,
    Property.Kind.IpRange,
)

private val DOC_VALUE_QUERY_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + setOf(
    Property.Kind.Boolean,
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val EXACT_KINDS = NUMERIC_KINDS + TERM_KINDS + setOf(
    Property.Kind.Boolean,
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Flattened,
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val CURSOR_SORT_KINDS = EXACT_KINDS - setOf(Property.Kind.UnsignedLong, Property.Kind.Flattened)

private val LITERAL_KINDS = TERM_KINDS

private val RANGE_KINDS = NUMERIC_KINDS + KEYWORD_KINDS + RANGE_FIELD_KINDS + setOf(
    Property.Kind.Date,
    Property.Kind.DateNanos,
    Property.Kind.Ip,
)

private val SEARCH_KINDS = setOf(
    Property.Kind.Text,
    Property.Kind.MatchOnlyText,
    Property.Kind.SearchAsYouType,
    Property.Kind.SemanticText,
)

private val STRING_KINDS = TERM_KINDS + SEARCH_KINDS + setOf(
    Property.Kind.Ip,
    Property.Kind.Version,
)

private val PHRASE_SEARCH_KINDS = SEARCH_KINDS - Property.Kind.SemanticText
private val MATCH_KINDS = SEARCH_KINDS + EXACT_KINDS

private val NORMALIZED_VALUE_CAPABILITIES = setOf(
    QueryCapability.EXACT_MATCH,
    QueryCapability.LITERAL_MATCH,
    QueryCapability.RANGE,
    QueryCapability.SORT,
    QueryCapability.CURSOR_SORT,
    QueryCapability.AGGREGATE_TERMS,
)
