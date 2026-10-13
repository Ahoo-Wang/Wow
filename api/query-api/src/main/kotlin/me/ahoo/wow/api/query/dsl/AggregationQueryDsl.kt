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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import java.time.ZoneId

/**
 * Builds an `aggregate` query: its [filter], the collections it [expand]s into elements, its groups (`terms`,
 * `histogram`, `dateHistogram`, `datePart`), its metrics, [sort] by group and metric aliases, [limit] and [having].
 * Inside an expanded element, a group or metric field is relative to the innermost element unless its scope is
 * [AggregationGroupScope.ROOT].
 */
@Suppress("TooManyFunctions")
@QueryDslMarker
class AggregationQueryDsl {
    private var filter: FilterExpression = MatchAllFilter
    private val elements = mutableListOf<AggregationElement>()
    private val groups = mutableListOf<AggregationGroup>()
    private val metrics = mutableListOf<AggregationMetric>()
    private var sort: List<Sort> = emptyList()
    private var limit: Int = AggregationQuery.DEFAULT_LIMIT
    private var having: HavingExpression? = null

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) = filter(filterOf(block))

    fun expand(path: String) = expand(QueryField(path))

    fun expand(path: QueryField) {
        elements += AggregationElement(path)
    }

    /** Expands [path] into its elements, keeping those that match [block] (fields relative to the element). */
    fun expand(path: String, block: FilterDsl.() -> Unit) = expand(QueryField(path), block)

    fun expand(path: QueryField, block: FilterDsl.() -> Unit) {
        elements += AggregationElement(path, FilterDsl(element = path).apply(block).build())
    }

    /** [scope] `ROOT` groups the expanded elements by a field of their root record ([AggregationGroup.scope]). */
    fun terms(field: String, alias: String, missingKey: String? = null, scope: AggregationGroupScope? = null) =
        terms(QueryField(field), alias, missingKey, scope)

    fun terms(field: QueryField, alias: String, missingKey: String? = null, scope: AggregationGroupScope? = null) {
        groups += AggregationGroup.Terms(field, alias, missingKey, scope = scope)
    }

    /** Groups by the value of [expression]. */
    fun terms(expression: AggregationExpression, alias: String, scope: AggregationGroupScope? = null) {
        groups += AggregationGroup.Terms(alias = alias, expression = expression, scope = scope)
    }

    fun histogram(field: String, interval: Double, alias: String, scope: AggregationGroupScope? = null) =
        histogram(QueryField(field), interval, alias, scope)

    fun histogram(field: QueryField, interval: Double, alias: String, scope: AggregationGroupScope? = null) {
        groups += AggregationGroup.Histogram(field, alias, interval, scope = scope)
    }

    /** Buckets the value of [expression] by [interval]. */
    fun histogram(
        expression: AggregationExpression,
        interval: Double,
        alias: String,
        scope: AggregationGroupScope? = null,
    ) {
        groups += AggregationGroup.Histogram(alias = alias, interval = interval, expression = expression, scope = scope)
    }

    fun dateHistogram(
        field: String,
        unit: AggregationDateUnit,
        alias: String,
        timeZone: ZoneId = UTC,
        dense: Boolean = false,
        scope: AggregationGroupScope? = null,
    ) = dateHistogram(QueryField(field), unit, alias, timeZone, dense, scope)

    fun dateHistogram(
        field: QueryField,
        unit: AggregationDateUnit,
        alias: String,
        timeZone: ZoneId = UTC,
        dense: Boolean = false,
        scope: AggregationGroupScope? = null,
    ) {
        groups += AggregationGroup.DateHistogram(field, alias, unit, timeZone.id, dense, scope)
    }

    fun datePart(
        field: String,
        part: AggregationDatePart,
        alias: String,
        timeZone: ZoneId = UTC,
        dense: Boolean = false,
        scope: AggregationGroupScope? = null,
    ) = datePart(QueryField(field), part, alias, timeZone, dense, scope)

    fun datePart(
        field: QueryField,
        part: AggregationDatePart,
        alias: String,
        timeZone: ZoneId = UTC,
        dense: Boolean = false,
        scope: AggregationGroupScope? = null,
    ) {
        groups += AggregationGroup.DatePart(field, alias, part, timeZone.id, dense, scope)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    /** Counts the records (or elements) that match [init]. */
    fun count(alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Count(alias, filterOf(init))
    }

    fun any(field: String, alias: String) = any(QueryField(field), alias)

    fun any(field: QueryField, alias: String) {
        metrics += AggregationMetric.Any(field, alias)
    }

    fun any(field: String, alias: String, init: FilterDsl.() -> Unit) = any(QueryField(field), alias, init)

    fun any(field: QueryField, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Any(field, alias, filterOf(init))
    }

    /** The value of [field] on the group's earliest record by [orderBy] (the model's event time by default). */
    fun first(field: String, alias: String, orderBy: String? = null) =
        first(QueryField(field), alias, orderBy?.let(::QueryField))

    fun first(field: QueryField, alias: String, orderBy: QueryField? = null) {
        metrics += AggregationMetric.First(field, alias, orderBy)
    }

    fun first(field: String, alias: String, orderBy: String? = null, init: FilterDsl.() -> Unit) =
        first(QueryField(field), alias, orderBy?.let(::QueryField), init)

    fun first(field: QueryField, alias: String, orderBy: QueryField? = null, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.First(field, alias, orderBy, filterOf(init))
    }

    /** The value of [field] on the group's latest record by [orderBy] (the model's event time by default). */
    fun last(field: String, alias: String, orderBy: String? = null) =
        last(QueryField(field), alias, orderBy?.let(::QueryField))

    fun last(field: QueryField, alias: String, orderBy: QueryField? = null) {
        metrics += AggregationMetric.Last(field, alias, orderBy)
    }

    fun last(field: String, alias: String, orderBy: String? = null, init: FilterDsl.() -> Unit) =
        last(QueryField(field), alias, orderBy?.let(::QueryField), init)

    fun last(field: QueryField, alias: String, orderBy: QueryField? = null, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Last(field, alias, orderBy, filterOf(init))
    }

    fun field(name: String): AggregationExpression = field(QueryField(name))

    fun field(name: QueryField): AggregationExpression = AggregationExpression.Field(name)

    fun constant(value: Double): AggregationExpression = AggregationExpression.Constant(value)

    /** The time from [from] to [to] in [unit]: `dateDiff("state.paidAt", "state.shippedAt", DateDiffUnit.HOUR)`. */
    fun dateDiff(from: String, to: String, unit: DateDiffUnit): AggregationExpression =
        dateDiff(QueryField(from), QueryField(to), unit)

    fun dateDiff(from: QueryField, to: QueryField, unit: DateDiffUnit): AggregationExpression =
        AggregationExpression.DateDiff(from, to, unit)

    operator fun AggregationExpression.plus(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.ADD, other)

    operator fun AggregationExpression.minus(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.SUBTRACT, other)

    operator fun AggregationExpression.times(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.MULTIPLY, other)

    operator fun AggregationExpression.div(other: AggregationExpression): AggregationExpression =
        binary(AggregationExpressionOperator.DIVIDE, other)

    private fun AggregationExpression.binary(
        operator: AggregationExpressionOperator,
        other: AggregationExpression,
    ): AggregationExpression = AggregationExpression.Binary(operator, this, other)

    fun sum(field: String, alias: String) = sum(field(field), alias)

    fun sum(field: String, alias: String, init: FilterDsl.() -> Unit) = sum(field(field), alias, init)

    fun avg(field: String, alias: String) = avg(field(field), alias)

    fun avg(field: String, alias: String, init: FilterDsl.() -> Unit) = avg(field(field), alias, init)

    fun min(field: String, alias: String) = min(field(field), alias)

    fun min(field: String, alias: String, init: FilterDsl.() -> Unit) = min(field(field), alias, init)

    fun max(field: String, alias: String) = max(field(field), alias)

    fun max(field: String, alias: String, init: FilterDsl.() -> Unit) = max(field(field), alias, init)

    fun sum(expression: AggregationExpression, alias: String) = numeric(AggregationFunction.SUM, expression, alias)

    fun sum(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.SUM, expression, alias, init)

    fun avg(expression: AggregationExpression, alias: String) = numeric(AggregationFunction.AVG, expression, alias)

    fun avg(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.AVG, expression, alias, init)

    fun min(expression: AggregationExpression, alias: String) = numeric(AggregationFunction.MIN, expression, alias)

    fun min(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.MIN, expression, alias, init)

    fun max(expression: AggregationExpression, alias: String) = numeric(AggregationFunction.MAX, expression, alias)

    fun max(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.MAX, expression, alias, init)

    fun distinctCount(field: String, alias: String) = distinctCount(field(field), alias)

    fun distinctCount(field: String, alias: String, init: FilterDsl.() -> Unit) =
        distinctCount(field(field), alias, init)

    fun distinctCount(expression: AggregationExpression, alias: String) {
        metrics += AggregationMetric.DistinctCount(expression, alias)
    }

    fun distinctCount(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.DistinctCount(expression, alias, filterOf(init))
    }

    fun stddev(field: String, alias: String) = stddev(field(field), alias)

    fun stddev(field: String, alias: String, init: FilterDsl.() -> Unit) = stddev(field(field), alias, init)

    fun stddev(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.STDDEV, expression, alias)

    fun stddev(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.STDDEV, expression, alias, init)

    fun variance(field: String, alias: String) = variance(field(field), alias)

    fun variance(field: String, alias: String, init: FilterDsl.() -> Unit) = variance(field(field), alias, init)

    fun variance(expression: AggregationExpression, alias: String) =
        numeric(AggregationFunction.VARIANCE, expression, alias)

    fun variance(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        numeric(AggregationFunction.VARIANCE, expression, alias, init)

    fun percentile(field: String, p: Double, alias: String) = percentile(field(field), p, alias)

    fun percentile(field: String, p: Double, alias: String, init: FilterDsl.() -> Unit) =
        percentile(field(field), p, alias, init)

    fun percentile(expression: AggregationExpression, p: Double, alias: String) {
        metrics += AggregationMetric.Percentile(expression, p, alias)
    }

    fun percentile(expression: AggregationExpression, p: Double, alias: String, init: FilterDsl.() -> Unit) {
        metrics += AggregationMetric.Percentile(expression, p, alias, filterOf(init))
    }

    fun median(field: String, alias: String) = median(field(field), alias)

    fun median(field: String, alias: String, init: FilterDsl.() -> Unit) = median(field(field), alias, init)

    fun median(expression: AggregationExpression, alias: String) = percentile(expression, MEDIAN, alias)

    fun median(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) =
        percentile(expression, MEDIAN, alias, init)

    fun derived(alias: String, expression: DerivedExpression) {
        metrics += AggregationMetric.Derived(alias, expression)
    }

    /** A metric computed from the other metrics of the group: `derived("share") { ref("sum") / total("sum") }`. */
    fun derived(alias: String, init: DerivedExpressionDsl.() -> DerivedExpression) =
        derived(alias, DerivedExpressionDsl().init())

    private fun numeric(function: AggregationFunction, expression: AggregationExpression, alias: String) {
        metrics += AggregationMetric.Numeric(function, expression, alias)
    }

    private fun numeric(
        function: AggregationFunction,
        expression: AggregationExpression,
        alias: String,
        init: FilterDsl.() -> Unit,
    ) {
        metrics += AggregationMetric.Numeric(function, expression, alias, filterOf(init))
    }

    fun sort(sort: List<Sort>) {
        this.sort = sort
    }

    /** Sorts the groups by group and metric aliases: `sort { "count".desc() }`. */
    fun sort(block: SortDsl.() -> Unit) = sort(SortDsl().apply(block).build())

    fun limit(limit: Int) {
        this.limit = limit
    }

    /** Keeps the groups whose metrics match: `having { ("count" gte 10.0) and ("sum".isNotNull()) }`. */
    fun having(init: HavingDsl.() -> HavingExpression) {
        having = HavingDsl().init()
    }

    fun build(): AggregationQuery = AggregationQuery(filter, elements, groups, metrics, sort, limit, having = having)

    private fun filterOf(block: FilterDsl.() -> Unit): FilterExpression = me.ahoo.wow.api.query.dsl.filter(block)

    private companion object {
        const val MEDIAN = 50.0
    }
}

/**
 * The expression context of [AggregationQueryDsl.derived]: [constant] returns a [DerivedExpression.Constant] rather
 * than the [AggregationExpression.Constant] of [AggregationQueryDsl.constant], so the two scopes do not share a
 * receiver type.
 */
@QueryDslMarker
class DerivedExpressionDsl {
    fun ref(metric: String): DerivedExpression = DerivedExpression.MetricRef(metric)

    fun constant(value: Double): DerivedExpression = DerivedExpression.Constant(value)

    /** The sum of [metric] over the groups sharing this group's [within] keys. */
    fun total(metric: String, vararg within: String): DerivedExpression =
        DerivedExpression.Total(metric, within.toList())

    /** [metric] accumulated along [order] within the groups sharing this group's [within] keys. */
    fun running(
        metric: String,
        order: Sort,
        within: List<String> = emptyList(),
        frame: Int? = null,
        function: RunningFunction = RunningFunction.SUM,
    ): DerivedExpression = DerivedExpression.Running(metric, order, within, frame, function)

    /** [metric] of the group [shift] buckets, or [period]s, earlier along the DATE_HISTOGRAM group [along]. */
    fun previous(
        metric: String,
        along: String,
        shift: Int = 1,
        period: AggregationDateUnit? = null,
    ): DerivedExpression = DerivedExpression.Previous(metric, along, shift, period)

    /** The dense rank of [metric] among the groups sharing this group's [within] keys. */
    fun rank(
        metric: String,
        vararg within: String,
        direction: Sort.Direction = Sort.Direction.DESC,
    ): DerivedExpression = DerivedExpression.Rank(metric, within.toList(), direction)

    operator fun DerivedExpression.plus(other: DerivedExpression): DerivedExpression =
        binary(AggregationExpressionOperator.ADD, other)

    operator fun DerivedExpression.minus(other: DerivedExpression): DerivedExpression =
        binary(AggregationExpressionOperator.SUBTRACT, other)

    operator fun DerivedExpression.times(other: DerivedExpression): DerivedExpression =
        binary(AggregationExpressionOperator.MULTIPLY, other)

    operator fun DerivedExpression.div(other: DerivedExpression): DerivedExpression =
        binary(AggregationExpressionOperator.DIVIDE, other)

    private fun DerivedExpression.binary(
        operator: AggregationExpressionOperator,
        other: DerivedExpression,
    ): DerivedExpression = DerivedExpression.Binary(operator, this, other)
}

/**
 * The expression context of [AggregationQueryDsl.having]: its [String.eq] takes a [Double] and yields a
 * [HavingExpression] rather than the [FilterExpression] of [FilterDsl], so the two scopes do not share a receiver
 * type. Each name is a metric alias.
 */
@QueryDslMarker
class HavingDsl {
    infix fun String.eq(value: Double): HavingExpression = condition(ComparisonOperator.EQ, value)

    infix fun String.ne(value: Double): HavingExpression = condition(ComparisonOperator.NE, value)

    infix fun String.gt(value: Double): HavingExpression = condition(ComparisonOperator.GT, value)

    infix fun String.gte(value: Double): HavingExpression = condition(ComparisonOperator.GTE, value)

    infix fun String.lt(value: Double): HavingExpression = condition(ComparisonOperator.LT, value)

    infix fun String.lte(value: Double): HavingExpression = condition(ComparisonOperator.LTE, value)

    fun String.between(lower: Double, upper: Double): HavingExpression = HavingExpression.Between(this, lower, upper)

    fun String.isIn(values: List<Double>): HavingExpression = HavingExpression.In(this, values)

    fun String.isNull(): HavingExpression = HavingExpression.IsNull(this)

    fun String.isNotNull(): HavingExpression = HavingExpression.IsNull(this, negated = true)

    infix fun HavingExpression.and(other: HavingExpression): HavingExpression =
        HavingExpression.And(listOf(this, other))

    infix fun HavingExpression.or(other: HavingExpression): HavingExpression = HavingExpression.Or(listOf(this, other))

    private fun String.condition(operator: ComparisonOperator, value: Double) =
        HavingExpression.Condition(this, operator, value)
}

/** The model's default zone, so a group naming none is the group its JSON without `timeZone` reads as. */
private val UTC: ZoneId = ZoneId.of("UTC")
