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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.QueryExecutionException
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Compiles an admitted aggregation of one aggregate type to SQL over its context's snapshot table (decision D131),
 * whose columns are `g0`, `g1`, ... for the groups and `m0`, `m1`, ... for the metrics, in the query's order.
 * Three layers, each only when needed:
 * 1. the groups: each record, or each element of the expanded paths (`CROSS JOIN LATERAL jsonb_array_elements`),
 *    filtered, grouped by the group keys (`GROUP BY 1, 2, ...`) with count, sum, avg, min, max, var_pop, stddev_pop,
 *    `count(DISTINCT)`, `percentile_disc`, ANY, FIRST and LAST, each metric under its own `FILTER (WHERE ...)`;
 * 2. the derived metrics, computed from the columns of the groups;
 * 3. HAVING, the order of the groups (`NULLS FIRST` ascending, as the core orders them) and the window's limit.
 *
 * Without groups the statement returns one summary row, `count` 0 and the other metrics `NULL` when no record
 * matched. Dense fill and the windows are the core's (`StorageSupport` RESIDUAL): they never arrive here. The columns
 * carry generated names, so no alias of the query reaches the SQL text.
 */
internal class PostgresAggregationCompiler(private val table: String, private val aggregateName: String) {
    @Suppress("LongMethod")
    fun compile(admitted: AdmittedQuery<AggregationQuery>, window: GroupWindow): SqlStatement {
        val query = admitted.query
        val parameters = SqlParameters()
        val compiler = PostgresFilterCompiler(admitted, parameters)
        val columns = HashMap<String, String>()
        query.groupBy.forEachIndexed { index, group -> columns[group.alias] = group(index) }
        query.metrics.forEachIndexed { index, metric -> columns[metric.alias] = metric(index) }

        // The text binds its values left to right: the derived metrics of layer 2 come before layer 1 in it.
        val derived = query.metrics.mapIndexedNotNull { index, metric ->
            (metric as? AggregationMetric.Derived)?.let {
                derived(parameters, query, columns, it.expression, hashSetOf(metric.alias)) + " AS " + metric(index)
            }
        }

        val from = StringBuilder(table).append(" AS t")
        val elementFilters = ArrayList<Pair<String, FilterExpression>>()
        query.elements.forEach { element ->
            val container = admitted.field(element.path)
            val alias = compiler.nextAlias()
            from.append(" CROSS JOIN LATERAL jsonb_array_elements(")
                .append(PostgresFilterCompiler.objectElements(compiler.node(container)))
                .append(") AS ").append(alias).append("(v)")
            compiler.enter(container.physicalField, "$alias.v")
            elementFilters += alias to element.filter
        }

        val present = ArrayList<String>()
        val select = ArrayList<String>()
        query.groupBy.forEachIndexed { index, group ->
            select += group(parameters, compiler, group, present) + " AS " + group(index)
        }
        query.metrics.forEachIndexed { index, metric ->
            if (metric !is AggregationMetric.Derived) {
                select += metric(parameters, compiler, admitted, metric) + " AS " + metric(index)
            }
        }

        val where = ArrayList<String>()
        where += "t.aggregate_name = " + parameters.bind(SqlParameter.Text(aggregateName))
        where += compiler.filter(query.filter)
        elementFilters.forEach { (alias, filter) ->
            where += "jsonb_typeof($alias.v) = 'object'"
            if (filter !== MatchAllFilter) where += compiler.filter(filter)
        }
        present.forEach { where += "$it IS NOT NULL" }

        var sql = "SELECT " + select.joinToString(", ") + " FROM " + from + " WHERE " + where.joinToString(" AND ")
        if (query.groupBy.isNotEmpty()) sql += " GROUP BY " + (1..query.groupBy.size).joinToString(", ")
        if (derived.isNotEmpty()) sql = "SELECT a.*, " + derived.joinToString(", ") + " FROM ($sql) AS a"

        val outer = StringBuilder("SELECT * FROM (").append(sql).append(") AS h")
        query.having?.let { outer.append(" WHERE ").append(having(parameters, columns, it)) }
        val order = query.effectiveSort().map {
            PostgresSnapshotSqlCompiler.direction("h." + checkNotNull(columns[it.field.path]), it.direction)
        }
        if (order.isNotEmpty()) outer.append(" ORDER BY ").append(order.joinToString(", "))
        if (window is GroupWindow.First) {
            outer.append(" LIMIT ").append(parameters.bind(SqlParameter.Int8(window.limit.toLong())))
        }
        return parameters.statement(outer.toString())
    }

    private fun group(
        parameters: SqlParameters,
        compiler: PostgresFilterCompiler,
        group: AggregationGroup,
        present: MutableList<String>,
    ): String = when (group) {
        is AggregationGroup.Terms -> {
            val expression = group.expression
            if (expression != null) {
                compiler.numeric(expression)
            } else {
                val value = compiler.typed(groupField(compiler, group)).first
                // Admission allows a missing key only on a single-valued string field.
                group.missingKey?.let { "coalesce($value, ${parameters.bind(SqlParameter.Text(it))})" } ?: value
            }
        }
        is AggregationGroup.Histogram -> {
            val value = group.expression?.let(compiler::numeric) ?: compiler.typed(groupField(compiler, group)).first
            val interval = BigDecimal.valueOf(group.interval)
            "(floor($value / ${parameters.bind(SqlParameter.Numeric(interval))}) * " +
                "${parameters.bind(SqlParameter.Numeric(interval))})"
        }
        is AggregationGroup.DateHistogram -> {
            val millis = compiler.epochMillis(group.field).also(present::add)
            val local = "to_timestamp($millis / 1000.0) AT TIME ZONE ${zone(parameters, group.timeZone)}"
            "(extract(epoch from (date_trunc('${unit(group.unit)}', $local) AT TIME ZONE " +
                "${zone(parameters, group.timeZone)})) * 1000)::bigint"
        }
        is AggregationGroup.DatePart -> {
            val millis = compiler.epochMillis(group.field).also(present::add)
            "extract(${part(group.part)} from (to_timestamp($millis / 1000.0) AT TIME ZONE " +
                "${zone(parameters, group.timeZone)}))::integer"
        }
    }

    private fun groupField(compiler: PostgresFilterCompiler, group: AggregationGroup) =
        compiler.field(checkNotNull(group.field) { "A group without an expression reads a field." })

    private fun metric(
        parameters: SqlParameters,
        compiler: PostgresFilterCompiler,
        admitted: AdmittedQuery<AggregationQuery>,
        metric: AggregationMetric,
    ): String = when (metric) {
        is AggregationMetric.Count -> "count(*)" + filter(compiler, metric.filter)
        is AggregationMetric.Numeric -> function(metric.function) + "(" + compiler.numeric(metric.expression) + ")" +
            filter(compiler, metric.filter)
        is AggregationMetric.DistinctCount -> {
            val expression = metric.expression
            val value = if (expression is me.ahoo.wow.api.query.AggregationExpression.Field) {
                compiler.typed(admitted.field(expression.field)).first
            } else {
                compiler.numeric(expression)
            }
            "count(DISTINCT $value)" + filter(compiler, metric.filter)
        }
        is AggregationMetric.Percentile -> {
            val fraction = parameters.bind(SqlParameter.Numeric(BigDecimal.valueOf(metric.percentile / PERCENT)))
            "percentile_disc($fraction::float8) WITHIN GROUP (ORDER BY ${compiler.numeric(metric.expression)})" +
                filter(compiler, metric.filter)
        }
        is AggregationMetric.Any -> {
            val node = compiler.node(admitted.field(metric.field))
            "(array_agg($node ORDER BY t.aggregate_id, t.tenant_id) FILTER (WHERE " +
                "coalesce(jsonb_typeof($node), 'null') <> 'null'" + and(compiler, metric.filter) + "))[1]"
        }
        is AggregationMetric.Edge -> {
            val node = compiler.node(admitted.field(metric.field))
            val orderBy = compiler.typed(
                admitted.field(checkNotNull(metric.orderBy) { "Admission names every FIRST / LAST orderBy." }),
            ).first
            val direction = if (metric is AggregationMetric.First) "ASC" else "DESC"
            "(array_agg($node ORDER BY $orderBy $direction, t.aggregate_id $direction, t.tenant_id $direction) " +
                "FILTER (WHERE $orderBy IS NOT NULL" + and(compiler, metric.filter) + "))[1]"
        }
        is AggregationMetric.Derived -> throw QueryExecutionException("A derived metric is not a column of the groups.")
    }

    private fun filter(compiler: PostgresFilterCompiler, filter: FilterExpression): String =
        if (filter === MatchAllFilter) "" else " FILTER (WHERE ${compiler.filter(filter)})"

    private fun and(compiler: PostgresFilterCompiler, filter: FilterExpression): String =
        if (filter === MatchAllFilter) "" else " AND ${compiler.filter(filter)}"

    /** A derived metric over the columns of the groups; a derived metric it names is inlined. */
    private fun derived(
        parameters: SqlParameters,
        query: AggregationQuery,
        columns: Map<String, String>,
        expression: DerivedExpression,
        visiting: MutableSet<String>,
    ): String = when (expression) {
        is DerivedExpression.MetricRef -> {
            val named = query.metrics.firstOrNull { it.alias == expression.metric }
            if (named is AggregationMetric.Derived) {
                requireExecution(visiting.add(named.alias)) { "Derived metric [${named.alias}] names itself." }
                derived(parameters, query, columns, named.expression, visiting).also { visiting.remove(named.alias) }
            } else {
                "a." + (columns[expression.metric] ?: throw QueryExecutionException("Unknown metric [${expression.metric}]."))
            }
        }
        is DerivedExpression.Constant -> parameters.bind(SqlParameter.Numeric(BigDecimal.valueOf(expression.value))) + "::numeric"
        is DerivedExpression.Binary -> {
            val left = derived(parameters, query, columns, expression.left, visiting)
            val right = derived(parameters, query, columns, expression.right, visiting)
            when (expression.operator) {
                AggregationExpressionOperator.ADD -> "($left + $right)"
                AggregationExpressionOperator.SUBTRACT -> "($left - $right)"
                AggregationExpressionOperator.MULTIPLY -> "($left * $right)"
                AggregationExpressionOperator.DIVIDE -> "($left / NULLIF($right, 0))"
            }
        }
        is DerivedExpression.Window -> throw QueryExecutionException("Windows are computed by the core.")
    }

    private fun having(parameters: SqlParameters, columns: Map<String, String>, having: HavingExpression): String =
        when (having) {
            is HavingExpression.And -> having.operands.joinToString(
                " AND ",
                "(",
                ")"
            ) { having(parameters, columns, it) }
            is HavingExpression.Or -> having.operands.joinToString(" OR ", "(", ")") { having(parameters, columns, it) }
            is HavingExpression.IsNull -> "h.${column(columns, having.metric)} IS " + if (having.negated) "NOT NULL" else "NULL"
            is HavingExpression.Condition -> "h.${column(columns, having.metric)} " +
                PostgresFilterCompiler.comparison(having.operator) + " " + number(parameters, having.value)
            is HavingExpression.Between -> "(h.${column(columns, having.metric)} >= ${number(parameters, having.lower)} " +
                "AND h.${column(columns, having.metric)} <= ${number(parameters, having.upper)})"
            is HavingExpression.In -> "h.${column(columns, having.metric)} = ANY(" +
                parameters.bind(SqlParameter.Array("numeric", having.values.map(BigDecimal::valueOf))) + ")"
        }

    private fun column(columns: Map<String, String>, alias: String): String =
        columns[alias] ?: throw QueryExecutionException("Unknown metric [$alias].")

    private fun number(parameters: SqlParameters, value: Double): String =
        parameters.bind(SqlParameter.Numeric(BigDecimal.valueOf(value)))

    companion object {
        private const val PERCENT = 100.0

        fun group(index: Int): String = "g$index"

        fun metric(index: Int): String = "m$index"

        fun function(function: AggregationFunction): String = when (function) {
            AggregationFunction.SUM -> "sum"
            AggregationFunction.AVG -> "avg"
            AggregationFunction.MIN -> "min"
            AggregationFunction.MAX -> "max"
            AggregationFunction.VARIANCE -> "var_pop"
            AggregationFunction.STDDEV -> "stddev_pop"
        }

        fun unit(unit: AggregationDateUnit): String = unit.name.lowercase()

        fun part(part: AggregationDatePart): String = when (part) {
            AggregationDatePart.DAY_OF_WEEK -> "isodow"
            AggregationDatePart.DAY_OF_MONTH -> "day"
            AggregationDatePart.HOUR_OF_DAY -> "hour"
            AggregationDatePart.MONTH_OF_YEAR -> "month"
        }

        /**
         * [timeZone] bound for `AT TIME ZONE`: a fixed offset as an interval (ISO sign, east positive), since PostgreSQL
         * reads a text offset such as `+08:00` with the POSIX sign; a region by its tz database ID, as Java names it.
         */
        fun zone(parameters: SqlParameters, timeZone: String): String {
            val zone = try {
                ZoneId.of(timeZone).normalized()
            } catch (error: DateTimeException) {
                throw QueryExecutionException("Time zone [$timeZone] is not a time zone.", error)
            }
            return if (zone is ZoneOffset) {
                parameters.bind(SqlParameter.Text("${zone.totalSeconds} seconds")) + "::interval"
            } else {
                parameters.bind(SqlParameter.Text(zone.id)) + "::text"
            }
        }
    }
}
