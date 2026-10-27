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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryViolation

/**
 * Translates an admitted aggregation into one statement:
 *
 * ```sql
 * SELECT __g0 AS `city`, __m0 AS `orders`, if(toFloat64(__m0) = 0, NULL, toFloat64(__m1) / toFloat64(__m0)) AS `aov`
 * FROM (SELECT <group> AS __g0, count() AS __m0, sumOrNull(...) AS __m1 FROM t WHERE ... GROUP BY __g0)
 * WHERE isNotNull(__g0) AND <having> ORDER BY `orders` DESC NULLS LAST, `city` ASC NULLS FIRST LIMIT 10
 * ```
 *
 * The inner query uses internal aliases, so a metric alias that equals a column name never shadows the column in
 * `WHERE` or in another metric (ClickHouse resolves aliases before columns); the outer query renames, derives,
 * filters by HAVING, sorts and limits. HAVING compares in IEEE double space, and a NULL metric satisfies no
 * comparison, as the protocol says. Windows and dense fill are the core's (`StorageSupport` RESIDUAL): they never
 * arrive here.
 */
internal class ClickHouseAggregationCompiler(
    private val admitted: AdmittedQuery<AggregationQuery>,
    table: ClickHouseTable,
    private val source: String,
) {
    private val parameters = SqlParameters()
    private val filters = ClickHouseFilterCompiler(admitted, table, parameters)
    private val query = admitted.query

    fun compile(window: GroupWindow): ClickHouseStatement {
        if (query.elements.isNotEmpty()) throw QueryViolation.StorageUnsupported("element scopes").rejection()
        val inner = buildList {
            query.groupBy.forEachIndexed { index, group -> add("${group(group)} AS ${group(index)}") }
            query.metrics.forEachIndexed { index, metric ->
                if (metric !is AggregationMetric.Derived) add("${metric(metric)} AS ${metric(index)}")
            }
        }
        val outer = buildList {
            query.groupBy.forEachIndexed { index, group -> add("${group(index)} AS ${quoteIdentifier(group.alias)}") }
            query.metrics.forEachIndexed { index, metric ->
                val value = if (metric is AggregationMetric.Derived) derived(metric.expression) else metric(index)
                add("$value AS ${quoteIdentifier(metric.alias)}")
            }
        }
        val conditions = query.groupBy.withIndex()
            .filter { (_, group) -> group.dropsMissingKeys() }
            .map { "isNotNull(${group(it.index)})" } + listOfNotNull(query.having?.let(::having))
        val sql = buildString {
            append("SELECT ").append(outer.joinToString(", "))
            append(" FROM (SELECT ").append(inner.joinToString(", "))
            append(" FROM ").append(source)
            append(" WHERE ").append(filters.compile(query.filter))
            if (query.groupBy.isNotEmpty()) {
                append(" GROUP BY ").append(query.groupBy.indices.joinToString(", ") { group(it) })
            }
            append(")")
            if (conditions.isNotEmpty()) append(" WHERE ").append(conditions.joinToString(" AND "))
            if (query.groupBy.isNotEmpty()) {
                append(" ORDER BY ").append(
                    query.effectiveSort().joinToString(", ") { ordered(quoteIdentifier(it.field.path), it.direction) },
                )
            }
            if (window is GroupWindow.First) append(" LIMIT ").append(window.limit)
        }
        return parameters.statement(sql)
    }

    /** A record whose date or expression input has no value belongs to no bucket; a TERMS field keeps a NULL key. */
    private fun AggregationGroup.dropsMissingKeys(): Boolean = this !is AggregationGroup.Terms || expression != null

    private fun group(group: AggregationGroup): String = when (group) {
        is AggregationGroup.Terms -> group.expression?.let(filters::expression) ?: run {
            val column = filters.column(checkNotNull(group.field))
            group.missingKey?.let { "ifNull($column, ${parameters.bind(it, "String")})" } ?: column
        }
        is AggregationGroup.Histogram -> {
            val input = group.expression?.let(filters::expression)
                ?: "toFloat64(${filters.column(checkNotNull(group.field))})"
            val interval = group.interval.sqlLiteral()
            "floor($input / $interval) * $interval"
        }
        is AggregationGroup.DateHistogram -> dateHistogram(group)
        is AggregationGroup.DatePart -> datePart(group)
    }

    /** The bucket of an instant: the start of its unit on the zone's wall clock, keyed as epoch milliseconds. */
    private fun dateHistogram(group: AggregationGroup.DateHistogram): String {
        val zone = group.timeZone.sqlTimeZone()
        val local = "toTimeZone(${filters.instant(group.field)}, $zone)"
        val start = when (group.unit) {
            AggregationDateUnit.YEAR -> "toStartOfYear($local)"
            AggregationDateUnit.QUARTER -> "toStartOfQuarter($local)"
            AggregationDateUnit.MONTH -> "toStartOfMonth($local)"
            AggregationDateUnit.WEEK -> "toMonday($local)"
            AggregationDateUnit.DAY -> "toStartOfDay($local)"
            AggregationDateUnit.HOUR -> "toStartOfHour($local)"
            AggregationDateUnit.MINUTE -> "toStartOfMinute($local)"
            AggregationDateUnit.SECOND -> "toDateTime($local)"
        }
        return "toInt64(toUnixTimestamp(toDateTime($start, $zone))) * 1000"
    }

    private fun datePart(group: AggregationGroup.DatePart): String {
        val local = "toTimeZone(${filters.instant(group.field)}, ${group.timeZone.sqlTimeZone()})"
        return when (group.part) {
            AggregationDatePart.DAY_OF_WEEK -> "toDayOfWeek($local)"
            AggregationDatePart.DAY_OF_MONTH -> "toDayOfMonth($local)"
            AggregationDatePart.HOUR_OF_DAY -> "toHour($local)"
            AggregationDatePart.MONTH_OF_YEAR -> "toMonth($local)"
        }
    }

    private fun metric(metric: AggregationMetric): String {
        val condition = metric.filter.takeUnless { it == MatchAllFilter }?.let(filters::compile)
        return when (metric) {
            is AggregationMetric.Count -> condition?.let { "countIf($it)" } ?: "count()"
            is AggregationMetric.Numeric ->
                aggregate(metric.function.native(), metric.expression.nativeInput(), condition)
            is AggregationMetric.DistinctCount -> {
                val input = metric.expression.nativeInput()
                condition?.let { "uniqExactIf($input, $it)" } ?: "uniqExact($input)"
            }
            is AggregationMetric.Percentile -> {
                val level = (metric.percentile / PERCENT).sqlLiteral()
                aggregate("quantileTDigest($level)", filters.expression(metric.expression), condition)
            }
            is AggregationMetric.Any -> aggregate("any", filters.column(metric.field), condition)
            is AggregationMetric.First -> positional("argMin", metric, condition)
            is AggregationMetric.Last -> positional("argMax", metric, condition)
            is AggregationMetric.Derived -> throw QueryExecutionException(
                "A derived metric is computed by the outer query."
            )
        }
    }

    /** `fnOrNull(x)` or `fnIfOrNull(x, cond)`: an empty set is NULL, as the protocol says for value metrics. */
    private fun aggregate(function: String, input: String, condition: String?): String {
        val parameterized = function.indexOf('(')
        val name = if (parameterized < 0) function else function.substring(0, parameterized)
        val levels = if (parameterized < 0) "" else function.substring(parameterized)
        return if (condition == null) "${name}OrNull$levels($input)" else "${name}IfOrNull$levels($input, $condition)"
    }

    /** FIRST/LAST: only records with both a value and an `orderBy` position (admission fills it in) take part. */
    private fun positional(function: String, metric: AggregationMetric.Edge, condition: String?): String {
        val value = filters.column(metric.field)
        val position = filters.column(checkNotNull(metric.orderBy) { "Admission names every FIRST / LAST orderBy." })
        val guard = listOfNotNull("isNotNull($value)", "isNotNull($position)", condition).joinToString(" AND ")
        return "${function}IfOrNull($value, $position, $guard)"
    }

    /**
     * A plain FIELD aggregates natively, so a Decimal sum keeps its exact value (decision D122) and a distinct count
     * of strings counts strings; any other expression is computed in Float64.
     */
    private fun AggregationExpression.nativeInput(): String =
        if (this is AggregationExpression.Field) filters.column(field) else filters.expression(this)

    /** Null propagates; a division by zero is NULL. */
    private fun derived(expression: DerivedExpression): String = when (expression) {
        is DerivedExpression.MetricRef -> {
            val index = query.metrics.indexOfFirst { it.alias == expression.metric }
            if (index < 0) throw QueryExecutionException("A derived metric names an unknown metric.")
            when (val metric = query.metrics[index]) {
                // A derived metric has no inner column: its arithmetic is inlined.
                is AggregationMetric.Derived -> derived(metric.expression)
                else -> "toFloat64(${metric(index)})"
            }
        }
        is DerivedExpression.Constant -> expression.value.sqlLiteral()
        is DerivedExpression.Binary ->
            ClickHouseFilterCompiler.arithmetic(
                expression.operator,
                derived(expression.left),
                derived(expression.right)
            )
        is DerivedExpression.Window -> throw QueryExecutionException("Windows are computed by the core.")
    }

    /** A HAVING condition over the outer query's metric aliases, in double space; NULL satisfies no comparison. */
    private fun having(expression: HavingExpression): String = when (expression) {
        is HavingExpression.Condition -> "ifNull(${metricValue(expression.metric)} " +
            "${ClickHouseFilterCompiler.comparison(expression.operator)} ${expression.value.sqlLiteral()}, 0)"
        is HavingExpression.Between -> "ifNull(${metricValue(expression.metric)} BETWEEN " +
            "${expression.lower.sqlLiteral()} AND ${expression.upper.sqlLiteral()}, 0)"
        is HavingExpression.In -> expression.values.joinToString(
            ", ",
            "ifNull(${metricValue(expression.metric)} IN (",
            "), 0)",
        ) { it.sqlLiteral() }
        is HavingExpression.IsNull ->
            "${if (expression.negated) "isNotNull" else "isNull"}(${metricValue(expression.metric)})"
        is HavingExpression.And -> expression.operands.joinToString(" AND ", "(", ")", transform = ::having)
        is HavingExpression.Or -> expression.operands.joinToString(" OR ", "(", ")", transform = ::having)
    }

    private fun metricValue(alias: String): String = "toFloat64(${quoteIdentifier(alias)})"

    private companion object {
        const val PERCENT = 100.0

        fun group(index: Int): String = "__g$index"

        fun metric(index: Int): String = "__m$index"

        fun AggregationFunction.native(): String = when (this) {
            AggregationFunction.SUM -> "sum"
            AggregationFunction.AVG -> "avg"
            AggregationFunction.MIN -> "min"
            AggregationFunction.MAX -> "max"
            AggregationFunction.STDDEV -> "stddevPop"
            AggregationFunction.VARIANCE -> "varPop"
        }
    }
}
