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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchNone
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.multiMatch
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.prefix
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import java.time.Instant

abstract class AbstractElasticsearchFilterCompiler(
    private val documentIdField: String? = null,
) {
    private val filterNormalizer = FilterNormalizer()

    fun compile(filter: FilterExpression, schema: QueryModelSchema): Query = compile(filter, schema, Instant.now())

    internal fun compile(filter: FilterExpression, schema: QueryModelSchema, now: Instant): Query =
        compileNormalized(filterNormalizer.normalize(filter, schema, now = now), schema, FilterScope())

    internal fun compileScoped(
        filter: FilterExpression,
        schema: QueryModelSchema,
        logicalParent: QueryField,
        physicalParent: QueryField,
        now: Instant,
    ): Query = compileNormalized(
        filterNormalizer.normalize(filter, schema, logicalParent, now),
        schema,
        FilterScope(logicalParent, physicalParent),
    )

    internal fun compilePhysical(filter: FilterExpression, parent: String? = null): Query =
        compileNormalized(
            filterNormalizer.normalize(filter),
            schema = null,
            scope = FilterScope(physicalParent = parent?.let(::QueryField)),
        )

    private data class FilterScope(
        val logicalParent: QueryField? = null,
        val physicalParent: QueryField? = null,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compileNormalized(
        filter: FilterExpression,
        schema: QueryModelSchema?,
        scope: FilterScope,
    ): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is IdFilter -> documentIdEqual(filter.value)
        is IdsFilter -> documentIdIn(filter.values)
        is AggregateIdFilter -> aggregateIdEqual(filter.value)
        is AggregateIdsFilter -> aggregateIdIn(filter.values)
        is TenantIdFilter -> term {
            it.field(QueryField(MessageRecords.TENANT_ID).metadataPath(schema))
                .value(filter.value)
        }
        is OwnerIdFilter -> term {
            it.field(QueryField(MessageRecords.OWNER_ID).metadataPath(schema))
                .value(filter.value)
        }
        is SpaceIdFilter -> term {
            it.field(QueryField(MessageRecords.SPACE_ID).metadataPath(schema))
                .value(filter.value)
        }
        is AndFilter -> bool {
            it.filter(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
        }
        is OrFilter -> bool {
            it.should(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
                .minimumShouldMatch("1")
        }
        is NorFilter -> bool {
            it.mustNot(filter.operands.map { operand -> compileNormalized(operand, schema, scope) })
        }
        is EqualFilter -> {
            val field = filter.field.path(schema, QueryCapability.EXACT_MATCH, scope)
            if (scope.physicalParent == null && field == DOCUMENT_ID_FIELD) {
                documentIdEqual(filter.value.requiredNativeValue().toString())
            } else {
                term { it.field(field).value(filter.value.fieldValue()) }
            }
        }
        is NotEqualFilter -> bool {
            it.mustNot(compileNormalized(EqualFilter(filter.field, filter.value), schema, scope))
        }
        is GreaterThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .lt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .lte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is ContainsFilter -> wildcard {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope))
                .value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope)).value(filter.value)
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(filter.field.path(schema, QueryCapability.LITERAL_MATCH, scope))
                .value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> {
            val field = filter.field.path(schema, QueryCapability.EXACT_MATCH, scope)
            if (scope.physicalParent == null && field == DOCUMENT_ID_FIELD) {
                documentIdIn(filter.values.map { value -> value.requiredNativeValue().toString() })
            } else {
                terms {
                    it.field(field).terms { terms ->
                        terms.value(filter.values.map { value -> value.fieldValue() })
                    }
                }
            }
        }
        is NotInFilter -> bool {
            it.mustNot(compileNormalized(InFilter(filter.field, filter.values), schema, scope))
        }
        is BetweenFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(schema, QueryCapability.RANGE, scope))
                    .gte(JsonData.of(filter.lowerBound.requiredNativeValue()))
                    .lte(JsonData.of(filter.upperBound.requiredNativeValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(filter.field.path(schema, QueryCapability.EXACT_MATCH, scope))
                    .terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        is IsNotNullFilter -> exists { it.field(filter.field.path(schema, QueryCapability.PRESENCE, scope)) }
        is ExistsFilter -> exists { it.field(filter.field.path(schema, QueryCapability.PRESENCE, scope)) }
        is NotExistsFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        is ElementMatchFilter -> nested {
            val nestedPath = filter.field.path(schema, QueryCapability.ELEMENT_SCOPE, scope)
            val nestedScope = FilterScope(
                logicalParent = scope.logicalParent?.append(filter.field) ?: filter.field,
                physicalParent = QueryField(nestedPath),
            )
            it.path(nestedPath).query(compileNormalized(filter.predicate, schema, nestedScope))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            val capability = when (filter.mode) {
                SearchMode.TERMS -> QueryCapability.FULL_TEXT_TERMS
                SearchMode.PHRASE -> QueryCapability.FULL_TEXT_PHRASE
            }
            if (filter.fields.isEmpty()) {
                if (schema != null && !schema.supports(capability)) {
                    throw QuerySchemaValidationException("Model does not support [$capability].")
                }
                it.lenient(true)
            } else {
                it.fields(filter.fields.map { field -> field.path(schema, capability, scope) })
            }
            if (filter.mode == SearchMode.PHRASE) it.type(TextQueryType.Phrase)
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term {
                it.field(QueryField(StateAggregateRecords.DELETED).metadataPath(schema)).value(false)
            }
            DeletionState.DELETED -> term {
                it.field(QueryField(StateAggregateRecords.DELETED).metadataPath(schema)).value(true)
            }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(schema, QueryCapability.PRESENCE, scope))
                }
            }
        }
        else -> error("Unsupported filter expression: ${filter::class.java.name}.")
    }

    private fun QueryField.path(
        schema: QueryModelSchema?,
        capability: QueryCapability,
        scope: FilterScope,
    ): String {
        if (schema == null) return path(scope.physicalParent?.path)
        val physicalField = schema.physicalField(this, capability, scope.logicalParent)
        if (scope.physicalParent != null && physicalField.relativeTo(scope.physicalParent) == null) {
            throw QuerySchemaValidationException("Physical field [$physicalField] is outside its nested scope.")
        }
        return physicalField.path
    }

    private fun QueryField.metadataPath(schema: QueryModelSchema?): String =
        path(schema, QueryCapability.EXACT_MATCH, FilterScope())

    private fun QueryField.path(parent: String?): String =
        if (parent == null || path == parent || path.startsWith("$parent.")) path else "$parent.$path"

    private companion object {
        const val DOCUMENT_ID_FIELD = "_id"
    }

    private fun documentIdEqual(value: String): Query = documentIdField?.let { field ->
        term { it.field(field).value(value) }
    } ?: ids { it.values(value) }

    private fun documentIdIn(values: List<String>): Query = documentIdField?.let { field ->
        terms { it.field(field).terms { terms -> terms.value(values.map(FieldValue::of)) } }
    } ?: ids { it.values(values) }

    protected open fun aggregateIdEqual(value: String): Query =
        term { it.field(MessageRecords.AGGREGATE_ID).value(value) }

    protected open fun aggregateIdIn(values: List<String>): Query =
        terms {
            it.field(MessageRecords.AGGREGATE_ID)
                .terms { terms -> terms.value(values.map(FieldValue::of)) }
        }

    private val StringComparison.ignoreCase: Boolean
        get() = this == StringComparison.CASE_INSENSITIVE

    private fun tools.jackson.databind.JsonNode.nativeValue(): Any? = when {
        isNull -> null
        isString -> asString()
        isNumber -> numberValue()
        isBoolean -> booleanValue()
        isPojo -> (this as tools.jackson.databind.node.POJONode).pojo
        isArray -> asSequence().map { it.nativeValue() }.toList()
        else -> throw QuerySchemaValidationException("Elasticsearch filter operands must be scalar.")
    }

    private fun tools.jackson.databind.JsonNode.requiredNativeValue(): Any {
        if (isPojo) {
            return JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(nativeValue()).requiredNativeValue()
        }
        val value = requireNotNull(nativeValue()) { "Filter value must be non-null." }
        val finite = when (value) {
            is Double -> value.isFinite()
            is Float -> value.isFinite()
            else -> true
        }
        if (!finite) {
            throw QuerySchemaValidationException("Elasticsearch numeric filter operands must be finite.")
        }
        if (value !is String && value !is Number && value !is Boolean) {
            throw QuerySchemaValidationException("Elasticsearch filter operands must be scalar.")
        }
        return value
    }

    private fun tools.jackson.databind.JsonNode.fieldValue(): FieldValue {
        val value = requiredNativeValue()
        return when (value) {
            is String -> FieldValue.of(value)
            is Boolean -> FieldValue.of(value)
            else -> FieldValue.of(value)
        }
    }
}

private fun String.escapeWildcard(): String =
    replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("?", "\\?")
