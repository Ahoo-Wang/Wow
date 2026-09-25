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
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.FilterNormalizer
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords

abstract class AbstractElasticsearchFilterCompiler(
    private val documentIdField: String? = null,
) {
    private val filterNormalizer = FilterNormalizer()

    /** Compiles an admitted count filter. */
    fun compile(admitted: AdmittedQuery<FilterExpression>): Query = compile(admitted.query, admitted)

    /**
     * Compiles [filter], a filter of [admitted] at any scope: each field's resolution carries its absolute physical
     * path, which nested queries and nested aggregations both address.
     */
    fun compile(filter: FilterExpression, admitted: AdmittedQuery<*>): Query =
        compileNormalized(filter, admitted, FilterScope())

    internal fun compilePhysical(filter: FilterExpression, parent: String? = null): Query =
        compileNormalized(
            filterNormalizer.normalize(filter),
            admitted = null,
            scope = FilterScope(physicalParent = parent?.let(::QueryField)),
        )

    /** The physical container of a framework-built physical filter; admitted filters carry their own. */
    private data class FilterScope(val physicalParent: QueryField? = null)

    /** [admitted] is `null` for a framework-built filter whose fields are already physical. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun compileNormalized(
        filter: FilterExpression,
        admitted: AdmittedQuery<*>?,
        scope: FilterScope,
    ): Query = when (filter) {
        MatchAllFilter -> matchAll { it }
        MatchNoneFilter -> matchNone { it }
        is IdFilter -> documentIdEqual(filter.value)
        is IdsFilter -> documentIdIn(filter.values)
        is AggregateIdFilter -> aggregateIdEqual(filter.value)
        is AggregateIdsFilter -> aggregateIdIn(filter.values)
        is TenantIdFilter -> term {
            it.field(filter.metadataPath(admitted, MessageRecords.TENANT_ID)).value(filter.value)
        }
        is OwnerIdFilter -> term {
            it.field(filter.metadataPath(admitted, MessageRecords.OWNER_ID)).value(filter.value)
        }
        is SpaceIdFilter -> term {
            it.field(filter.metadataPath(admitted, MessageRecords.SPACE_ID)).value(filter.value)
        }
        is AndFilter -> bool {
            it.filter(filter.operands.map { operand -> compileNormalized(operand, admitted, scope) })
        }
        is OrFilter -> bool {
            it.should(filter.operands.map { operand -> compileNormalized(operand, admitted, scope) })
                .minimumShouldMatch("1")
        }
        is NorFilter -> bool {
            it.mustNot(filter.operands.map { operand -> compileNormalized(operand, admitted, scope) })
        }
        is EqualFilter -> {
            val field = filter.field.path(admitted, scope)
            if (scope.physicalParent == null && field == DOCUMENT_ID_FIELD) {
                documentIdEqual(filter.value.requiredNativeValue().toString())
            } else {
                term { it.field(field).value(filter.value.fieldValue()) }
            }
        }
        is NotEqualFilter -> bool {
            it.mustNot(compileNormalized(EqualFilter(filter.field, filter.value), admitted, scope))
        }
        is GreaterThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(admitted, scope))
                    .gt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is GreaterThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(admitted, scope))
                    .gte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(admitted, scope))
                    .lt(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is LessThanOrEqualFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(admitted, scope))
                    .lte(JsonData.of(filter.value.requiredNativeValue()))
            }
        }
        is ContainsFilter -> wildcard {
            it.field(filter.field.path(admitted, scope))
                .value("*${filter.value.escapeWildcard()}*")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is StartsWithFilter -> prefix {
            it.field(filter.field.path(admitted, scope)).value(filter.value)
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is EndsWithFilter -> wildcard {
            it.field(filter.field.path(admitted, scope))
                .value("*${filter.value.escapeWildcard()}")
                .caseInsensitive(filter.stringComparison.ignoreCase)
        }
        is InFilter -> {
            val field = filter.field.path(admitted, scope)
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
            it.mustNot(compileNormalized(InFilter(filter.field, filter.values), admitted, scope))
        }
        is BetweenFilter -> range {
            it.untyped { range ->
                range.field(filter.field.path(admitted, scope))
                    .gte(JsonData.of(filter.lowerBound.requiredNativeValue()))
                    .lte(JsonData.of(filter.upperBound.requiredNativeValue()))
            }
        }
        is ContainsAllFilter -> {
            val values = filter.values.map { it.fieldValue() }
            termsSet {
                it.field(filter.field.path(admitted, scope))
                    .terms(values).minimumShouldMatch(values.size.toString())
            }
        }
        is IsNullFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(admitted, scope))
                }
            }
        }
        is IsNotNullFilter -> exists { it.field(filter.field.path(admitted, scope)) }
        is ExistsFilter -> exists { it.field(filter.field.path(admitted, scope)) }
        is NotExistsFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(admitted, scope))
                }
            }
        }
        is ElementMatchFilter -> nested {
            val nestedPath = filter.field.path(admitted, scope)
            val nestedScope = FilterScope(physicalParent = QueryField(nestedPath))
            it.path(nestedPath).query(compileNormalized(filter.predicate, admitted, nestedScope))
        }
        is SearchFilter -> multiMatch {
            it.query(filter.query)
            if (filter.fields.isEmpty()) {
                it.lenient(true)
            } else {
                it.fields(filter.fields.map { field -> field.path(admitted, scope) })
            }
            if (filter.mode == SearchMode.PHRASE) it.type(TextQueryType.Phrase)
            it
        }
        is DeletionFilter -> when (filter.deletionState) {
            DeletionState.ACTIVE -> term {
                it.field(filter.metadataPath(admitted, StateAggregateRecords.DELETED)).value(false)
            }
            DeletionState.DELETED -> term {
                it.field(filter.metadataPath(admitted, StateAggregateRecords.DELETED)).value(true)
            }
            DeletionState.ALL -> matchAll { it }
        }
        is IsEmptyFilter -> bool {
            it.mustNot { query ->
                query.exists { exists ->
                    exists.field(filter.field.path(admitted, scope))
                }
            }
        }

        is IsEmptyStringFilter, is IsNotEmptyStringFilter, is RelativeTimeFilter ->
            error("Filter [${filter.operator}] must be normalized before compilation.")
    }

    private fun QueryField.path(admitted: AdmittedQuery<*>?, scope: FilterScope): String =
        admitted?.field(this)?.physicalField?.path ?: path(scope.physicalParent?.path)

    /** The physical path of the system field [filter] targets; [physical] names it in a framework-built filter. */
    private fun FilterExpression.metadataPath(admitted: AdmittedQuery<*>?, physical: String): String =
        admitted?.systemField(this)?.physicalField?.path ?: physical

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
