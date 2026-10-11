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
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.QueryExecutionException

/**
 * Translates an admitted aggregation into one statement:
 *
 * ```sql
 * SELECT __g0 AS `city`, __m0 AS `orders`, if(__m0 = 0, NULL, __m1 / __m0) AS `aov`
 * FROM (SELECT <group> AS __g0, count() AS __m0, sumOrNull(...) AS __m1 FROM t WHERE ... GROUP BY __g0)
 * WHERE isNotNull(__g0) ORDER BY `orders` DESC, `city` ASC LIMIT 10
 * ```
 *
 * The inner query uses internal aliases, so a metric alias that equals a column name never shadows the column in
 * `WHERE` or in another metric (ClickHouse resolves aliases before columns); the outer query renames, derives, sorts
 * and limits.
 */
internal class ClickHouseAggregationCompiler(
    private val admitted: AdmittedQuery<AggregationQuery>,
    private val table: ClickHouseTable,
) {
    private val parameters = SqlParameters()
    private val filters = ClickHouseFilterCompiler(admitted, table, parameters)
    private val query = admitted.query

    fun compile(window: GroupWindow): ClickHouseStatement {
        if (query.elements.isNotEmpty()) throw QueryExecutionException("ClickHouse datasets have no element scopes.")
        val inner = buildList {
            query.groupBy.forEachIndexed { index, group -> add("${group(group)} AS __g$index") }
            query.metrics.forEachIndexed { index, metric ->
                if (metric !is AggregationMetric.Derived) add("${metric(metric)} AS __m$index")
            }
        }
        val outer = buildList {
            query.groupBy.forEachIndexed { index, group -> add("__g$index AS ${quoteIdentifier(group.alias)}") }
            query.metrics.forEachIndexed { index, metric ->
                val value = if (metric is AggregationMetric.Derived) derived(metric.expression) else "__m$index"
                add("$value AS ${quoteIdentifier(metric.alias)}")
            }
        }
        val sql = buildString {
            append("SELECT ").append(outer.joinToString(", "))
            append(" FROM (SELECT ").append(inner.joinToString(", "))
            append(" FROM ").append(table.qualifiedName)
            append(" WHERE ").append(filters.compile(query.filter))
            if (query.groupBy.isNotEmpty()) {
                append(" GROUP BY ").append(query.groupBy.indices.joinToString(", ") { "__g$it" })
            }
            append(")")
            val keyed = query.groupBy.withIndex().filter { (_, group) -> group.dropsMissingKeys() }
            if (keyed.isNotEmpty()) {
                append(" WHERE ").append(keyed.joinToString(" AND ") { "isNotNull(__g${it.index})" })
            }
            if (query.groupBy.isNotEmpty()) {
                append(" ORDER BY ").append(query.effectiveSort().joinToString(", ", transform = ::order))
            }
            if (window is GroupWindow.First) append(" LIMIT ").append(window.limit)
        }
        return parameters.statement(sql)
    }

    private fun order(sort: Sort): String =
        "${quoteIdentifier(sort.field.path)} ${if (sort.direction == Sort.Direction.ASC) "ASC" else "DESC"}"

    /** A record whose date or expression input has no value belongs to no bucket; a TERMS field keeps a NULL key. */
    private fun AggregationGroup.dropsMissingKeys(): Boolean = this !is AggregationGroup.Terms || expression != null

    private fun group(group: AggregationGroup): String = when (group) {
        is AggregationGroup.Terms -> group.expression?.let(filters::expression) ?: run {
            val field = checkNotNull(group.field)
            val column = filters.column(field)
            group.missingKey?.let { "ifNull($column, ${parameters.bind(it, "String")})" } ?: column
        }
        is AggregationGroup.Histogram -> {
            val input = group.expression?.let(filters::expression) ?: "toFloat64(${filters.column(checkNotNull(group.field))})"
            val interval = group.interval.sqlLiteral()
            "floor($input / $interval) * $interval"
        }
        is AggregationGroup.DateHistogram -> {
            val zone = group.timeZone.sqlTimeZone()
            val local = "toTimeZone(${filters.column(group.field)}, $zone)"
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
            "toInt64(toUnixTimestamp(toDateTime($start, $zone))) * 1000"
        }
        is AggregationGroup.DatePart -> {
            val local = "toTimeZone(${filters.column(group.field)}, ${group.timeZone.sqlTimeZone()})"
            when (group.part) {
                AggregationDatePart.DAY_OF_WEEK -> "toDayOfWeek($local)"
                AggregationDatePart.DAY_OF_MONTH -> "toDayOfMonth($local)"
                AggregationDatePart.HOUR_OF_DAY -> "toHour($local)"
                AggregationDatePart.MONTH_OF_YEAR -> "toMonth($local)"
            }
        }
    }

    private fun metric(metric: AggregationMetric): String {
        val condition = metric.filter.takeUnless { it == MatchAllFilter }?.let(filters::compile)
        return when (metric) {
            is AggregationMetric.Count -> condition?.let { "countIf($it)" } ?: "count()"
            is AggregationMetric.Numeric -> aggregate(metric.function.native(), metric.expression.numericInput(), condition)
            is AggregationMetric.DistinctCount -> {
                val input = metric.expression.distinctInput()
                condition?.let { "uniqExactIf($input, $it)" } ?: "uniqExact($input)"
            }
            is AggregationMetric.Percentile -> {
                val level = (metric.percentile / 100.0).sqlLiteral()
                aggregate("quantileTDigest($level)", filters.expression(metric.expression), condition)
            }
            is AggregationMetric.Any -> aggregate("any", filters.column(metric.field), condition)
            is AggregationMetric.First -> positional("argMin", metric.field, metric.orderBy, condition)
            is AggregationMetric.Last -> positional("argMax", metric.field, metric.orderBy, condition)
            is AggregationMetric.Derived -> error("Derived metrics are computed by the outer query.")
        }
    }

    /** `fnOrNull(x)` or `fnIfOrNull(x, cond)`: an empty set is NULL, as the spec says for value metrics. */
    private fun aggregate(function: String, input: String, condition: String?): String {
        val parameterized = function.indexOf('(')
        val name = if (parameterized < 0) function else function.substring(0, parameterized)
        val levels = if (parameterized < 0) "" else function.substring(parameterized)
        return if (condition == null) "${name}OrNull$levels($input)" else "${name}IfOrNull$levels($input, $condition)"
    }

    /** FIRST/LAST: only records with both a value and an `orderBy` position take part. */
    private fun positional(
        function: String,
        field: me.ahoo.wow.api.query.QueryField,
        orderBy: me.ahoo.wow.api.query.QueryField?,
        condition: String?,
    ): String {
        val ordering = orderBy ?: throw QueryExecutionException("A dataset FIRST/LAST metric needs an orderBy.")
        val value = filters.column(field)
        val position = filters.column(ordering)
        val guard = listOfNotNull("isNotNull($value)", "isNotNull($position)", condition).joinToString(" AND ")
        return "${function}IfOrNull($value, $position, $guard)"
    }

    /**
     * A plain non-temporal FIELD aggregates natively, so a Decimal sum keeps its precision (the spec: "已声明的标量
     * FIELD 保留原生聚合及其数值精度"); any other expression is computed in Float64.
     */
    private fun AggregationExpression.numericInput(): String =
        if (this is AggregationExpression.Field && !filters.isTemporal(field)) filters.column(field) else filters.expression(this)

    /** A FIELD keeps its native value, so strings count as strings; any other expression is numeric. */
    private fun AggregationExpression.distinctInput(): String = when (this) {
        is AggregationExpression.Field -> filters.column(field)
        else -> filters.expression(this)
    }

    private fun AggregationFunction.native(): String = when (this) {
        AggregationFunction.SUM -> "sum"
        AggregationFunction.AVG -> "avg"
        AggregationFunction.MIN -> "min"
        AggregationFunction.MAX -> "max"
        AggregationFunction.STDDEV -> "stddevPop"
        AggregationFunction.VARIANCE -> "varPop"
    }

    /** Null propagates; a division by zero is NULL. */
    private fun derived(expression: DerivedExpression): String = when (expression) {
        is DerivedExpression.MetricRef -> "toFloat64(__m${metricIndex(expression.metric)})"
        is DerivedExpression.Constant -> expression.value.sqlLiteral()
        is DerivedExpression.Binary -> {
            val left = derived(expression.left)
            val right = derived(expression.right)
            when (expression.operator) {
                AggregationExpressionOperator.ADD -> "($left + $right)"
                AggregationExpressionOperator.SUBTRACT -> "($left - $right)"
                AggregationExpressionOperator.MULTIPLY -> "($left * $right)"
                AggregationExpressionOperator.DIVIDE -> "if($right = 0, NULL, $left / $right)"
            }
        }
    }

    private fun metricIndex(alias: String): Int = query.metrics.indexOfFirst { it.alias == alias }.also {
        check(it >= 0) { "Derived metric references unknown metric [$alias]." }
    }
}
