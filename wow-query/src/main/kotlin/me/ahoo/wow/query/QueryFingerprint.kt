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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.filter.QueryType
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The SHA-256 of the shape of [query], a [queryType] query as submitted: see [queryShapeOf].
 */
internal fun fingerprintOf(queryType: QueryType, query: Any): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(queryShapeOf(queryType, query).toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

/**
 * The structure of [query], walked node by node rather than serialized and redacted: operators, fields, the number of
 * values an operator was given, sort, projection, groups, metrics and the paging kind and size. Nothing the caller
 * supplied as a value is emitted (filter values and bounds, search text, day counts, offsets, time zones, date
 * patterns, constants, histogram intervals, percentiles, missing keys, page index, cursor, aliases), so two queries
 * that differ only in values have one shape. Every `when` is exhaustive over its sealed type: a new node does not
 * compile until its shape is decided here.
 */
internal fun queryShapeOf(queryType: QueryType, query: Any): String = QueryShapeWriter().apply {
    token(queryType.name)
    when (queryType) {
        QueryType.SINGLE -> queryable(query as ISingleQuery)
        QueryType.LIST -> {
            val list = query as IListQuery
            queryable(list)
            token("limit", list.limit)
        }
        QueryType.PAGED -> {
            val paged = query as IPagedQuery
            queryable(paged)
            token("size", paged.pagination.size)
        }
        QueryType.CURSOR -> {
            val cursor = query as ICursorQuery
            queryable(cursor)
            token("size", cursor.size)
            token(if (cursor.cursor == null) "first" else "next")
        }
        QueryType.COUNT -> filter(query as FilterExpression)
        QueryType.AGGREGATION -> aggregation(query as AggregationQuery)
    }
}.toString()

@Suppress("TooManyFunctions")
private class QueryShapeWriter {
    private val shape = StringBuilder()

    fun token(name: String) {
        shape.append(name).append(' ')
    }

    fun token(name: String, count: Int) {
        shape.append(name).append('=').append(count).append(' ')
    }

    private fun field(field: QueryField?) {
        token(field?.path ?: "-")
    }

    private inline fun group(name: String, body: () -> Unit) {
        shape.append(name).append('(')
        body()
        shape.append(") ")
    }

    fun queryable(query: Queryable<*>) {
        filter(query.filter)
        projection(query.projection)
        sort(query.sort) { field(it) }
    }

    private fun projection(projection: Projection) = group("projection") {
        group("include") { projection.include.forEach(::field) }
        group("exclude") { projection.exclude.forEach(::field) }
    }

    private inline fun sort(sort: List<Sort>, crossinline field: (QueryField) -> Unit) = group("sort") {
        sort.forEach {
            field(it.field)
            token(it.direction.name)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun filter(filter: FilterExpression): Unit = group(filter.operator.name) {
        when (filter) {
            is MatchAllFilter, is MatchNoneFilter -> Unit
            is IdFilter, is AggregateIdFilter, is TenantIdFilter, is OwnerIdFilter, is SpaceIdFilter -> Unit
            is IdsFilter -> token("values", filter.values.size)
            is AggregateIdsFilter -> token("values", filter.values.size)
            is AndFilter -> filter.operands.forEach(::filter)
            is OrFilter -> filter.operands.forEach(::filter)
            is NorFilter -> filter.operands.forEach(::filter)
            is DeletionFilter -> token(filter.deletionState.name)
            is ElementMatchFilter -> {
                field(filter.field)
                filter(filter.predicate)
            }
            is SearchFilter -> {
                token(filter.mode.name)
                filter.fields.forEach(::field)
            }
            is EqualFilter -> {
                field(filter.field)
                token("values", filter.value.arity())
            }
            is NotEqualFilter -> {
                field(filter.field)
                token("values", filter.value.arity())
            }
            is GreaterThanFilter -> field(filter.field)
            is GreaterThanOrEqualFilter -> field(filter.field)
            is LessThanFilter -> field(filter.field)
            is LessThanOrEqualFilter -> field(filter.field)
            is BetweenFilter -> field(filter.field)
            is ContainsFilter -> {
                field(filter.field)
                token(filter.stringComparison.name)
            }
            is StartsWithFilter -> {
                field(filter.field)
                token(filter.stringComparison.name)
            }
            is EndsWithFilter -> {
                field(filter.field)
                token(filter.stringComparison.name)
            }
            is InFilter -> {
                field(filter.field)
                token("values", filter.values.size)
            }
            is NotInFilter -> {
                field(filter.field)
                token("values", filter.values.size)
            }
            is ContainsAllFilter -> {
                field(filter.field)
                token("values", filter.values.size)
            }
            is IsEmptyFilter -> field(filter.field)
            is IsEmptyStringFilter -> field(filter.field)
            is IsNotEmptyStringFilter -> field(filter.field)
            is IsNullFilter -> field(filter.field)
            is IsNotNullFilter -> field(filter.field)
            is ExistsFilter -> field(filter.field)
            is NotExistsFilter -> field(filter.field)
            // BEFORE_TODAY's time, the day counts and the offsets are values: only the target is shape.
            is RelativeTimeFilter -> {
                field(filter.field)
                token(filter.timeUnit.name)
            }
            is ExpressionFilter -> {
                expression(filter.expression)
                token(filter.comparison.name)
            }
        }
    }

    fun aggregation(query: AggregationQuery) {
        val references = buildMap {
            query.groupBy.forEachIndexed { index, group -> put(group.alias, "group#$index") }
            query.metrics.forEachIndexed { index, metric -> put(metric.alias, "metric#$index") }
        }
        val reference: (String) -> String = { references[it] ?: "?" }
        filter(query.filter)
        group("elements") {
            query.elements.forEach { element ->
                field(element.path)
                filter(element.filter)
            }
        }
        group("groupBy") { query.groupBy.forEach(::group) }
        group("metrics") { query.metrics.forEach { metric(it, reference) } }
        sort(query.sort) { token(reference(it.path)) }
        token("limit", query.limit)
        query.having?.let { group("having") { having(it, reference) } }
    }

    private fun group(group: AggregationGroup) = when (group) {
        is AggregationGroup.Terms -> group("TERMS") {
            field(group.field)
            group.expression?.let(::expression)
            if (group.missingKey != null) token("missingKey")
        }
        is AggregationGroup.Histogram -> group("HISTOGRAM") {
            field(group.field)
            group.expression?.let(::expression)
        }
        is AggregationGroup.DateHistogram -> group("DATE_HISTOGRAM") {
            field(group.field)
            token(group.unit.name)
            if (group.dense) token("dense")
        }
        is AggregationGroup.DatePart -> group("DATE_PART") {
            field(group.field)
            token(group.part.name)
            if (group.dense) token("dense")
        }
    }

    private fun metric(metric: AggregationMetric, reference: (String) -> String) {
        when (metric) {
            is AggregationMetric.Count -> group("COUNT") { filter(metric.filter) }
            is AggregationMetric.Numeric -> group("NUMERIC") {
                token(metric.function.name)
                expression(metric.expression)
                filter(metric.filter)
            }
            is AggregationMetric.Any -> group("ANY") {
                field(metric.field)
                filter(metric.filter)
            }
            is AggregationMetric.DistinctCount -> group("DISTINCT_COUNT") {
                expression(metric.expression)
                filter(metric.filter)
            }
            is AggregationMetric.Percentile -> group("PERCENTILE") {
                expression(metric.expression)
                filter(metric.filter)
            }
            is AggregationMetric.Derived -> group("DERIVED") { derived(metric.expression, reference) }
            is AggregationMetric.First -> group("FIRST") { edge(metric) }
            is AggregationMetric.Last -> group("LAST") { edge(metric) }
        }
    }

    private fun edge(metric: AggregationMetric.Edge) {
        field(metric.field)
        field(metric.orderBy)
        filter(metric.filter)
    }

    private fun expression(expression: AggregationExpression): Unit = when (expression) {
        is AggregationExpression.Field -> field(expression.field)
        is AggregationExpression.Constant -> token("constant")
        is AggregationExpression.Binary -> group(expression.operator.name) {
            expression(expression.left)
            expression(expression.right)
        }
        is AggregationExpression.DateDiff -> group("DATE_DIFF") {
            field(expression.from)
            field(expression.to)
            token(expression.unit.name)
        }
    }

    private fun derived(expression: DerivedExpression, reference: (String) -> String): Unit = when (expression) {
        is DerivedExpression.MetricRef -> token(reference(expression.metric))
        is DerivedExpression.Constant -> token("constant")
        is DerivedExpression.Binary -> group(expression.operator.name) {
            derived(expression.left, reference)
            derived(expression.right, reference)
        }
    }

    private fun having(expression: HavingExpression, reference: (String) -> String): Unit = when (expression) {
        is HavingExpression.Condition -> group(expression.operator.name) { token(reference(expression.metric)) }
        is HavingExpression.Between -> group("BETWEEN") { token(reference(expression.metric)) }
        is HavingExpression.In -> group("IN") {
            token(reference(expression.metric))
            token("values", expression.values.size)
        }
        is HavingExpression.IsNull -> group(if (expression.negated) "IS_NOT_NULL" else "IS_NULL") {
            token(reference(expression.metric))
        }
        is HavingExpression.And -> group("AND") { expression.operands.forEach { having(it, reference) } }
        is HavingExpression.Or -> group("OR") { expression.operands.forEach { having(it, reference) } }
    }

    override fun toString(): String = shape.toString().trimEnd()
}

/** How many values an EQ or NE was given: the elements of an array, otherwise one. */
private fun JsonNode.arity(): Int = if (isArray) size() else 1
