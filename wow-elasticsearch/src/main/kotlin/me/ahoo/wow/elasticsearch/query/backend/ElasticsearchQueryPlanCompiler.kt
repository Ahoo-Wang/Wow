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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.search.SourceConfig
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.plan.QueryPlanV1
import me.ahoo.wow.query.schema.QueryFieldUsage

internal class ElasticsearchQueryPlanCompiler(
    private val binding: ElasticsearchQueryFieldBinding,
    private val nativeTemplates: ElasticsearchNativeQueryTemplateRegistry,
) {
    private val presence = ElasticsearchQueryPresenceBinding(binding)

    fun query(expression: QueryExpression): Query = compile(expression, null)

    fun sort(plan: QueryPlanV1): List<SortOptions> = plan.sort.map { sort ->
        SortOptions.of { option ->
            option.field { field ->
                field.field(binding.physical(sort.field, QueryFieldUsage.SORT))
                    .order(if (sort.direction == QuerySortDirection.ASC) SortOrder.Asc else SortOrder.Desc)
            }
        }
    }

    fun sourceFilter(plan: QueryPlanV1): SourceConfig? {
        val fields = resultFields(plan)
        if (fields.isEmpty()) {
            return null
        }
        return SourceConfig.of { source ->
            source.filter { filter -> filter.includes(fields.map(binding::source).distinct()) }
        }
    }

    fun resultProjection(plan: QueryPlanV1): Map<LogicalField, String> = binding.projection(resultFields(plan))

    private fun resultFields(plan: QueryPlanV1): Set<LogicalField> = when (val shape = plan.authorizedResultShape) {
        QueryPlanResultShape.Count -> emptySet()
        is QueryPlanResultShape.Dynamic -> shape.fields
        is QueryPlanResultShape.Typed -> shape.fields
    }

    private fun compile(expression: QueryExpression, relativeLogical: LogicalField?): Query = when (expression) {
        MatchAll -> Query.of { it.matchAll { match -> match } }
        MatchNone -> Query.of { it.matchNone { match -> match } }
        is LogicalExpression -> logical(expression.operator, expression.operands, relativeLogical)
        is PortableLogicalExpression -> logical(expression.operator, expression.operands, relativeLogical)
        is PredicateExpression -> predicate(expression, relativeLogical)
        is ElementMatchExpression -> elementMatch(expression, relativeLogical)
        is FullTextExpression -> fullText(expression, relativeLogical)
        is NativeExpression -> native(expression, relativeLogical)
        is RelativeTimeExpression -> error("Relative time was not normalized.")
    }

    private fun logical(
        operator: LogicalOperator,
        operands: List<QueryExpression>,
        relativeLogical: LogicalField?,
    ): Query {
        val queries = operands.map { operand -> compile(operand, relativeLogical) }
        return Query.of { query ->
            query.bool { bool ->
                when (operator) {
                    LogicalOperator.AND -> bool.filter(queries)
                    LogicalOperator.OR -> bool.should(queries).minimumShouldMatch("1")
                    LogicalOperator.NOR -> bool.mustNot(queries)
                }
            }
        }
    }

    private fun predicate(expression: PredicateExpression, relativeLogical: LogicalField?): Query {
        val logical = resolve(relativeLogical, expression.field)
        val field = binding.physical(logical)
        return when (expression.operator) {
            PortableOperator.EQ,
            PortableOperator.NE,
            PortableOperator.IN,
            PortableOperator.NOT_IN,
            PortableOperator.ALL_IN,
            -> equalityPredicate(expression, logical, field)

            PortableOperator.GT,
            PortableOperator.LT,
            PortableOperator.GTE,
            PortableOperator.LTE,
            PortableOperator.BETWEEN,
            -> orderedPredicate(expression, logical, field)

            PortableOperator.CONTAINS,
            PortableOperator.STARTS_WITH,
            PortableOperator.ENDS_WITH,
            -> stringPredicate(expression, field)

            PortableOperator.NULL,
            PortableOperator.NOT_NULL,
            PortableOperator.EXISTS,
            -> presencePredicate(expression, logical)

            PortableOperator.TRUE -> term(field, FieldValue.TRUE)
            PortableOperator.FALSE -> term(field, FieldValue.FALSE)
        }
    }

    private fun equalityPredicate(
        expression: PredicateExpression,
        logical: LogicalField,
        field: String,
    ): Query = when (expression.operator) {
        PortableOperator.EQ -> equality(logical, field, expression.values.single())
        PortableOperator.NE -> presentAndNot(logical, equality(logical, field, expression.values.single()))
        PortableOperator.IN -> membership(logical, field, expression.values, negate = false)
        PortableOperator.NOT_IN -> membership(logical, field, expression.values, negate = true)
        PortableOperator.ALL_IN -> and(
            expression.values.map { value -> term(field, binding.fieldValue(logical, value)) },
        )

        else -> invalidOperator(expression.operator)
    }

    private fun equality(logical: LogicalField, field: String, value: QueryValue): Query =
        if (value == QueryValue.NullValue) {
            presenceTerm(presence.explicitNull(logical))
        } else {
            term(field, binding.fieldValue(logical, value))
        }

    private fun orderedPredicate(
        expression: PredicateExpression,
        logical: LogicalField,
        field: String,
    ): Query = when (expression.operator) {
        PortableOperator.GT -> range(logical, field, RangeKind.GT, expression.values.single())
        PortableOperator.LT -> range(logical, field, RangeKind.LT, expression.values.single())
        PortableOperator.GTE -> range(logical, field, RangeKind.GTE, expression.values.single())
        PortableOperator.LTE -> range(logical, field, RangeKind.LTE, expression.values.single())
        PortableOperator.BETWEEN -> between(logical, field, expression.values[0], expression.values[1])
        else -> invalidOperator(expression.operator)
    }

    private fun stringPredicate(expression: PredicateExpression, field: String): Query = when (expression.operator) {
        PortableOperator.CONTAINS -> wildcard(field, expression, prefix = "*", suffix = "*")
        PortableOperator.STARTS_WITH -> wildcard(field, expression, prefix = "", suffix = "*")
        PortableOperator.ENDS_WITH -> wildcard(field, expression, prefix = "*", suffix = "")
        else -> invalidOperator(expression.operator)
    }

    private fun presencePredicate(expression: PredicateExpression, logical: LogicalField): Query =
        when (expression.operator) {
            PortableOperator.NULL -> presenceTerm(presence.explicitNull(logical))
            PortableOperator.NOT_NULL -> presentAndNot(logical, presenceTerm(presence.explicitNull(logical)))
            PortableOperator.EXISTS -> {
                val query = presenceTerm(presence.present(logical))
                if ((expression.values.single() as QueryValue.BooleanValue).value) query else not(query)
            }

            else -> invalidOperator(expression.operator)
        }

    private fun invalidOperator(operator: PortableOperator): Nothing =
        error("Unexpected portable operator group: $operator")

    private fun membership(
        logical: LogicalField,
        field: String,
        values: List<QueryValue>,
        negate: Boolean,
    ): Query {
        val nonNull = values.filterNot { it == QueryValue.NullValue }
        val matches = ArrayList<Query>()
        if (nonNull.isNotEmpty()) {
            matches += Query.of { query ->
                query.terms { terms ->
                    terms.field(field).terms { termsField ->
                        termsField.value(nonNull.map { value -> binding.fieldValue(logical, value) })
                    }
                }
            }
        }
        if (QueryValue.NullValue in values) {
            matches += presenceTerm(presence.explicitNull(logical))
        }
        val any = if (matches.size == 1) matches.single() else or(matches)
        return if (negate) presentAndNot(logical, any) else and(listOf(presenceTerm(presence.present(logical)), any))
    }

    private fun range(logical: LogicalField, field: String, kind: RangeKind, value: QueryValue): Query {
        val range = Query.of { query ->
            query.range { range ->
                range.untyped { untyped ->
                    untyped.field(field).let { builder ->
                        when (kind) {
                            RangeKind.GT -> builder.gt(binding.jsonValue(logical, value))
                            RangeKind.LT -> builder.lt(binding.jsonValue(logical, value))
                            RangeKind.GTE -> builder.gte(binding.jsonValue(logical, value))
                            RangeKind.LTE -> builder.lte(binding.jsonValue(logical, value))
                        }
                    }
                }
            }
        }
        return and(listOf(presenceTerm(presence.present(logical)), range))
    }

    private fun between(logical: LogicalField, field: String, lower: QueryValue, upper: QueryValue): Query {
        val range = Query.of { query ->
            query.range { range ->
                range.untyped { untyped ->
                    untyped.field(field)
                        .gte(binding.jsonValue(logical, lower))
                        .lte(binding.jsonValue(logical, upper))
                }
            }
        }
        return and(listOf(presenceTerm(presence.present(logical)), range))
    }

    private fun wildcard(field: String, expression: PredicateExpression, prefix: String, suffix: String): Query {
        val literal = (expression.values.single() as QueryValue.StringValue).value.escapeWildcard()
        val caseInsensitive = when (expression.stringComparison) {
            StringComparisonMode.DEFAULT,
            StringComparisonMode.CASE_SENSITIVE -> false
            StringComparisonMode.CASE_INSENSITIVE -> true
        }
        return Query.of { query ->
            query.wildcard { wildcard ->
                wildcard.field(field).value("$prefix$literal$suffix").caseInsensitive(caseInsensitive)
            }
        }
    }

    private fun elementMatch(expression: ElementMatchExpression, relativeLogical: LogicalField?): Query {
        val logical = resolve(relativeLogical, expression.field)
        val path = binding.physical(logical, QueryFieldUsage.NESTED)
        return Query.of { query ->
            query.nested { nested ->
                nested.path(path).query(compile(expression.predicate, logical)).scoreMode(
                    co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode.None,
                )
            }
        }
    }

    private fun fullText(expression: FullTextExpression, relativeLogical: LogicalField?): Query {
        if (expression.capabilityId.value != FULL_TEXT_CAPABILITY) {
            unsupported()
        }
        val fields = expression.fields.map { field ->
            val logical = resolve(relativeLogical, field)
            if (!binding.contains(logical) ||
                expression.capabilityId !in binding.schema(logical).capabilities
            ) {
                unsupported()
            }
            binding.physical(logical, QueryFieldUsage.SEARCH)
        }
        return Query.of { query ->
            query.multiMatch { multi -> multi.query(expression.query).fields(fields) }
        }
    }

    private fun native(expression: NativeExpression, relativeLogical: LogicalField?): Query {
        if (expression.capabilityId.value != NATIVE_CAPABILITY || expression.backendId != BACKEND_ID ||
            expression.declaredFields.any { field -> !binding.contains(resolve(relativeLogical, field)) }
        ) {
            unsupported()
        }
        return nativeTemplates.template(expression.templateId)?.build(expression.parameters) ?: unsupported()
    }

    private fun presenceTerm(term: PresenceTerm): Query = term(term.field, FieldValue.of(term.directName))

    private fun term(field: String, value: FieldValue): Query = Query.of { query ->
        query.term { term -> term.field(field).value(value) }
    }

    private fun presentAndNot(logical: LogicalField, excluded: Query): Query = Query.of { query ->
        query.bool { bool ->
            bool.filter(presenceTerm(presence.present(logical))).mustNot(excluded)
        }
    }

    private fun and(queries: List<Query>): Query = when (queries.size) {
        1 -> queries.single()
        else -> Query.of { query -> query.bool { bool -> bool.filter(queries) } }
    }

    private fun or(queries: List<Query>): Query = Query.of { query ->
        query.bool { bool -> bool.should(queries).minimumShouldMatch("1") }
    }

    private fun not(query: Query): Query = Query.of { outer -> outer.bool { bool -> bool.mustNot(query) } }

    private fun resolve(relativeTo: LogicalField?, field: LogicalField): LogicalField =
        if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

    private fun String.escapeWildcard(): String = buildString(length + 8) {
        this@escapeWildcard.forEach { character ->
            if (character == '*' || character == '?' || character == '\\') {
                append('\\')
            }
            append(character)
        }
    }

    private fun unsupported(): Nothing = throw QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED,
    )

    private enum class RangeKind {
        GT,
        LT,
        GTE,
        LTE,
    }

    private companion object {
        const val BACKEND_ID: String = "elasticsearch"
        const val FULL_TEXT_CAPABILITY: String = "full-text"
        const val NATIVE_CAPABILITY: String = "x-wow:elasticsearch-native"
    }
}
