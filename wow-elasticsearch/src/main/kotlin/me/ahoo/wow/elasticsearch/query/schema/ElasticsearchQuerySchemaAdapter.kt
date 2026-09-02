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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
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
            val invalidNestedParents = mapping.invalidNestedParents(logicalSchema)
            val nestedPaths = mapping.fields.filterValues { it.kind == Property.Kind.Nested }.keys
            val rootSearchFields = mapping.fields.filterKeys { path ->
                nestedPaths.none { path.startsWith("$it.") }
            }.values
            return QueryModelSchema(
                model = model,
                capabilities = buildSet {
                    if (rootSearchFields.any(ElasticsearchMappedField::supportsModelFullText)) {
                        add(QueryCapability.FULL_TEXT_TERMS)
                    }
                    if (rootSearchFields.any(ElasticsearchMappedField::supportsModelPhraseSearch)) {
                        add(QueryCapability.FULL_TEXT_PHRASE)
                    }
                },
                fields = buildMap {
                    logicalSchema.fields.forEach { (field, logical) ->
                        put(
                            field,
                            logical.toFieldSchema(
                                source = field,
                                projectionField = (
                                    mapping.fields[field.path]?.projectionPath
                                        ?: field.path.takeIf { field.path !in mapping.fields }
                                    )?.let(::QueryField),
                                bindings = BUILT_IN_CAPABILITIES.mapNotNull { capability ->
                                    mapping.binding(field, logical, capability, invalidNestedParents)
                                        ?.let { capability to it }
                                }.toMap(),
                            ),
                        )
                    }
                    putIfAbsent(
                        QueryField(DOCUMENT_ID_FIELD),
                        metadataField(DOCUMENT_ID_FIELD, QueryValueType.STRING, QueryCapability.EXACT_MATCH),
                    )
                    METADATA_SORT_FIELDS.forEach { (path, valueType) ->
                        putIfAbsent(QueryField(path), metadataField(path, valueType, QueryCapability.SORT))
                    }
                },
            )
        }

        private val METADATA_SORT_FIELDS = linkedMapOf(
            "_score" to QueryValueType.DECIMAL,
            "_doc" to QueryValueType.INTEGER,
            "_shard_doc" to QueryValueType.INTEGER,
        )

        private const val DOCUMENT_ID_FIELD = "_id"

        private val BUILT_IN_CAPABILITIES = listOf(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.FULL_TEXT_TERMS,
            QueryCapability.FULL_TEXT_PHRASE,
            QueryCapability.SORT,
            QueryCapability.ELEMENT_SCOPE,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
            QueryCapability.AGGREGATE_TEMPORAL,
        )

        private fun ElasticsearchIndexMapping.binding(
            source: QueryField,
            logical: LogicalQueryFieldSchema,
            capability: QueryCapability,
            invalidNestedParents: Set<String>,
        ): QueryFieldBinding? {
            val physicalPath = source.path
            if (invalidNestedParents.any { physicalPath.startsWith("$it.") }) return null
            if (logical.dynamicChildren && capability != QueryCapability.ELEMENT_SCOPE) return null
            val mapped = find(physicalPath) ?: return null
            val flattenedDescendant = physicalPath !in fields && mapped.kind == Property.Kind.Flattened
            val selected = if (mapped.supports(capability, logical, flattenedDescendant)) {
                physicalPath to mapped
            } else {
                mapped.selectMultiField(this, capability, logical) ?: return null
            }
            val selectedField = QueryField(selected.first)
            return QueryFieldBinding(
                resolvedField = selectedField,
                physicalField = selectedField,
                storageType = QueryStorageType(selected.second.kind.jsonValue()),
            )
        }

        private fun ElasticsearchMappedField.selectMultiField(
            mapping: ElasticsearchIndexMapping,
            capability: QueryCapability,
            logical: LogicalQueryFieldSchema,
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

        private fun LogicalQueryFieldSchema.toFieldSchema(
            source: QueryField,
            projectionField: QueryField?,
            bindings: Map<QueryCapability, QueryFieldBinding>,
        ): QueryFieldSchema {
            val rewrites = bindings.values.map { it.resolvedField != source }.distinct()
            val rewriteMode = when {
                semanticType is Temporal || QueryCapability.ELEMENT_SCOPE in bindings -> QueryRewriteMode.INFER
                bindings.isEmpty() || rewrites == listOf(false) -> QueryRewriteMode.NONE
                rewrites == listOf(true) -> QueryRewriteMode.REQUIRED
                else -> QueryRewriteMode.INFER
            }
            return QueryFieldSchema(
                title = title,
                description = description,
                enumValues = enumValues,
                valueTypes = valueTypes,
                nullable = nullable,
                required = required,
                cardinality = cardinality,
                semanticType = semanticType,
                dynamicChildren = false,
                bindings = bindings,
                projectionField = projectionField,
                rewriteMode = rewriteMode,
                maskRule = maskRule,
            )
        }

        private fun metadataField(
            path: String,
            valueType: QueryValueType,
            capability: QueryCapability,
        ): QueryFieldSchema {
            val source = QueryField(path)
            val bindings = mapOf(
                capability to QueryFieldBinding(source, source, storageType = null),
            )
            val rewrites = bindings.values.map { it.resolvedField != source }.distinct()
            val rewriteMode = when {
                QueryCapability.ELEMENT_SCOPE in bindings -> QueryRewriteMode.INFER
                bindings.isEmpty() || rewrites == listOf(false) -> QueryRewriteMode.NONE
                rewrites == listOf(true) -> QueryRewriteMode.REQUIRED
                else -> QueryRewriteMode.INFER
            }
            return QueryFieldSchema(
                title = null,
                description = null,
                enumValues = null,
                valueTypes = setOf(valueType),
                nullable = false,
                required = false,
                cardinality = QueryCardinality.SINGLE,
                semanticType = null,
                dynamicChildren = false,
                bindings = bindings,
                projectionField = null,
                rewriteMode = rewriteMode,
            )
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun ElasticsearchMappedField.supports(
    capability: QueryCapability,
    logical: LogicalQueryFieldSchema,
    flattenedDescendant: Boolean = false,
): Boolean {
    val executable = when (capability) {
        QueryCapability.PRESENCE -> queryable
        QueryCapability.EXACT_MATCH -> queryable && kind in EXACT_KINDS
        QueryCapability.LITERAL_MATCH -> indexed && kind in LITERAL_KINDS
        QueryCapability.RANGE -> queryable && kind in RANGE_KINDS
        QueryCapability.FULL_TEXT_TERMS -> indexed && kind in SEARCH_KINDS
        QueryCapability.FULL_TEXT_PHRASE -> indexed && kind in PHRASE_SEARCH_KINDS
        QueryCapability.SORT -> sortable && (kind in EXACT_KINDS || (indexed && kind == Property.Kind.Text))
        QueryCapability.ELEMENT_SCOPE -> kind == Property.Kind.Nested
        QueryCapability.AGGREGATE_TERMS -> aggregatable && (kind in EXACT_KINDS || kind == Property.Kind.Text)
        QueryCapability.AGGREGATE_NUMERIC -> aggregatable && kind in NUMERIC_KINDS
        QueryCapability.AGGREGATE_TEMPORAL -> aggregatable && when (logical.semanticType) {
            Temporal.Date -> kind == Property.Kind.Date || kind == Property.Kind.DateNanos
            is Temporal.Epoch -> kind in NUMERIC_KINDS
            else -> false
        }
        else -> false
    }
    return executable && (
        capability == QueryCapability.PRESENCE ||
            logical.proves(capability, kind) ||
            flattenedDescendant && capability == QueryCapability.EXACT_MATCH &&
            logical.valueTypes == setOf(QueryValueType.STRING)
        )
}

private val LogicalQueryFieldSchema.isElementScope: Boolean
    get() = cardinality == QueryCardinality.MANY && QueryValueType.OBJECT in valueTypes

private fun ElasticsearchIndexMapping.invalidNestedParents(logicalSchema: LogicalQuerySchema): Set<String> =
    fields.filterValues { it.kind == Property.Kind.Nested }.keys.filterTo(linkedSetOf()) { path ->
        logicalSchema.fields[QueryField(path)]?.isElementScope != true
    }

private fun LogicalQueryFieldSchema.proves(capability: QueryCapability, kind: Property.Kind): Boolean =
    storageRequirements(capability).let { requirements ->
        requirements.isNotEmpty() && requirements.all { kind in it }
    }

private fun LogicalQueryFieldSchema.storageRequirements(
    capability: QueryCapability,
): List<Set<Property.Kind>> = when (capability) {
    QueryCapability.EXACT_MATCH -> valueRequirements()
    QueryCapability.LITERAL_MATCH,
    QueryCapability.FULL_TEXT_TERMS,
    QueryCapability.FULL_TEXT_PHRASE,
    -> stringRequirements()
    QueryCapability.RANGE -> rangeRequirements()
    QueryCapability.SORT,
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

private fun LogicalQueryFieldSchema.valueRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date,
    is Temporal.Epoch,
    -> temporalRequirements()
    else -> valueTypes.map(QueryValueType::storageKinds)
}

private fun LogicalQueryFieldSchema.stringRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date,
    is Temporal.Epoch,
    -> emptyList()
    else -> valueTypes.filter { it == QueryValueType.STRING }.map { STRING_KINDS }
}

private fun LogicalQueryFieldSchema.numericRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date -> emptyList()
    is Temporal.Epoch -> temporalRequirements()
    else -> valueTypes.mapNotNull {
        when (it) {
            QueryValueType.INTEGER -> INTEGER_KINDS
            QueryValueType.DECIMAL -> NUMERIC_KINDS
            else -> null
        }
    }
}

private fun LogicalQueryFieldSchema.rangeRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    is Temporal.Formatted -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(KEYWORD_KINDS) else emptyList()
    else -> temporalRequirements().ifEmpty { numericRequirements().ifEmpty { stringRequirements() } }
}

private fun LogicalQueryFieldSchema.temporalRequirements(): List<Set<Property.Kind>> = when (semanticType) {
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

private fun ElasticsearchMappedField.supportsModelFullText(): Boolean = indexed && kind in MATCH_KINDS

private fun ElasticsearchMappedField.supportsModelPhraseSearch(): Boolean = indexed && kind in PHRASE_SEARCH_KINDS

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
