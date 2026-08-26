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
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import reactor.core.publisher.Mono

class ElasticsearchQuerySchemaAdapter(
    private val indexName: String,
    private val mappingResolver: ElasticsearchIndexMappingResolver,
) : QuerySchemaBackendAdapter {
    override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> =
        load(logicalSchema, mappingResolver.currentOrLoad(indexName))

    override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> =
        load(logicalSchema, mappingResolver.refresh(indexName))

    private fun load(
        logicalSchema: LogicalQuerySchema,
        mapping: Mono<ElasticsearchIndexMapping>,
    ): Mono<QueryModelSchema> = mapping.map { bind(logicalSchema, it) }
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
        ): QueryModelSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = buildSet {
                if (mapping.fields.values.any(ElasticsearchMappedField::supportsModelFullText)) {
                    add(QueryCapability.FULL_TEXT_TERMS)
                }
                if (mapping.fields.values.any(ElasticsearchMappedField::supportsModelPhraseSearch)) {
                    add(QueryCapability.FULL_TEXT_PHRASE)
                }
            },
            fields = logicalSchema.fields.mapValues { (field, logical) ->
                logical.toFieldSchema(
                    BUILT_IN_CAPABILITIES.mapNotNull { capability ->
                        mapping.binding(field.value, logical, capability)?.let { capability to it }
                    }.toMap(),
                )
            },
        )

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
            physicalPath: String,
            logical: LogicalQueryFieldSchema,
            capability: QueryCapability,
        ): QueryFieldBinding? {
            val mapped = find(physicalPath) ?: return null
            val selected = if (mapped.supports(capability, logical)) {
                physicalPath to mapped
            } else {
                mapped.selectMultiField(this, capability, logical) ?: return null
            }
            return QueryFieldBinding(
                physicalPath = selected.first,
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

        private fun LogicalQueryFieldSchema.toFieldSchema(bindings: Map<QueryCapability, QueryFieldBinding>) =
            QueryFieldSchema(
                title = title,
                description = description,
                enumValues = enumValues,
                valueTypes = valueTypes,
                nullable = nullable,
                required = required,
                cardinality = cardinality,
                semanticType = semanticType,
                dynamicChildren = dynamicChildren,
                bindings = bindings,
            )
    }
}

@Suppress("CyclomaticComplexMethod")
private fun ElasticsearchMappedField.supports(
    capability: QueryCapability,
    logical: LogicalQueryFieldSchema,
): Boolean = when (capability) {
    QueryCapability.PRESENCE -> queryable
    QueryCapability.EXACT_MATCH -> queryable && kind in EXACT_KINDS
    QueryCapability.LITERAL_MATCH -> indexed && kind in LITERAL_KINDS
    QueryCapability.RANGE -> queryable && kind in RANGE_KINDS
    QueryCapability.FULL_TEXT_TERMS -> indexed && kind in SEARCH_KINDS
    QueryCapability.FULL_TEXT_PHRASE -> indexed && kind in PHRASE_SEARCH_KINDS
    QueryCapability.SORT -> sortable && (kind in EXACT_KINDS || (indexed && kind == Property.Kind.Text))
    QueryCapability.ELEMENT_SCOPE -> kind == Property.Kind.Nested
    QueryCapability.AGGREGATE_TERMS -> aggregatable && kind in EXACT_KINDS
    QueryCapability.AGGREGATE_NUMERIC -> aggregatable && kind in NUMERIC_KINDS
    QueryCapability.AGGREGATE_TEMPORAL -> aggregatable && when (logical.semanticType) {
        Temporal.Date -> kind == Property.Kind.Date || kind == Property.Kind.DateNanos
        is Temporal.Epoch -> kind in NUMERIC_KINDS
        else -> false
    }
    else -> false
}

private val ElasticsearchMappedField.queryable: Boolean
    get() = indexed || (sortable && kind in DOC_VALUE_QUERY_KINDS)

private fun ElasticsearchMappedField.supportsModelFullText(): Boolean = indexed && kind in MATCH_KINDS

private fun ElasticsearchMappedField.supportsModelPhraseSearch(): Boolean = indexed && kind in PHRASE_SEARCH_KINDS

private val INTEGER_KINDS = setOf(
    Property.Kind.Byte,
    Property.Kind.Short,
    Property.Kind.Integer,
    Property.Kind.Long,
    Property.Kind.UnsignedLong,
)

private val NUMERIC_KINDS = INTEGER_KINDS + setOf(
    Property.Kind.HalfFloat,
    Property.Kind.Float,
    Property.Kind.Double,
    Property.Kind.ScaledFloat,
    Property.Kind.TokenCount,
)

private val KEYWORD_KINDS = setOf(
    Property.Kind.Keyword,
    Property.Kind.ConstantKeyword,
    Property.Kind.CountedKeyword,
    Property.Kind.IcuCollationKeyword,
)

private val TERM_KINDS = KEYWORD_KINDS + Property.Kind.Wildcard

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

private val PHRASE_SEARCH_KINDS = SEARCH_KINDS - Property.Kind.SemanticText
private val MATCH_KINDS = SEARCH_KINDS + EXACT_KINDS
