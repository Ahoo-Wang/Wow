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

package me.ahoo.wow.query.memory

import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.QueryExecutionException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Runs an admitted aggregation over records in memory: the elements expanded, the groups keyed, the metrics computed
 * per group, the groups ordered by the query's effective sort and the window applied. HAVING, top-N by a metric, dense
 * fill and windows are declared RESIDUAL by the in-memory storage, so the core computes them and they never arrive
 * here.
 */
internal class InMemoryAggregation(private val admitted: AdmittedQuery<AggregationQuery>) {
    private val query = admitted.query
    private val filters = InMemoryFilters(admitted)

    fun run(records: List<ObjectNode>, window: GroupWindow): List<ObjectNode> {
        val scopes = records.map(::RecordScope).filter { filters.matches(query.filter, it) }.flatMap(::expand)
        val groups = LinkedHashMap<List<JsonNode?>, MutableList<RecordScope>>()
        scopes.forEach { scope ->
            val keys = query.groupBy.map { key(it, scope) }
            if (keys.any { it == UNGROUPED }) return@forEach
            groups.getOrPut(keys) { mutableListOf() }.add(scope)
        }
        // Without groups and without a match there is no row: the core emits the empty summary, as with MongoDB.
        if (groups.isEmpty()) return emptyList()
        val rows = groups.map { (keys, members) -> row(keys, members) }
        val sorted = rows.sortedWith(comparator(query.effectiveSort()))
        return when (window) {
            GroupWindow.All -> sorted
            is GroupWindow.First -> sorted.take(window.limit)
        }
    }

    /** The scopes [scope] expands to: one per innermost element that passes every element filter. */
    private fun expand(scope: RecordScope): List<RecordScope> = query.elements.fold(listOf(scope)) { current, element ->
        val container = admitted.field(element.path)
        current.flatMap { outer ->
            outer.values(container).flattened().filter { it.isObject }
                .map { outer.enter(container.physicalField, it) }
                .filter { filters.matches(element.filter, it) }
        }
    }

    private fun groupScope(group: AggregationGroup, scope: RecordScope): RecordScope =
        if (group.scope == AggregationGroupScope.ROOT) RecordScope(scope.record) else scope

    /** The key of [group] for [scope]; [UNGROUPED] when the scope has no key, so it joins no group. */
    @Suppress("CyclomaticComplexMethod") // One exhaustive table: a branch per kind of group.
    private fun key(group: AggregationGroup, scope: RecordScope): JsonNode? {
        val at = groupScope(group, scope)
        return when (group) {
            is AggregationGroup.Terms -> {
                val expression = group.expression
                if (expression != null) {
                    filters.expression(expression, at)?.let(nodes::numberNode) ?: nodes.nullNode()
                } else {
                    val value = at.values(admitted.field(checkNotNull(group.field))).flattened().firstOrNull()
                    when {
                        value != null && !value.isNull -> value
                        group.missingKey != null -> nodes.stringNode(group.missingKey)
                        else -> nodes.nullNode()
                    }
                }
            }
            is AggregationGroup.Histogram -> {
                val value = group.expression?.let { filters.expression(it, at) }
                    ?: at.values(admitted.field(checkNotNull(group.field))).singleOrNull()?.numberOrNull()
                value?.let { nodes.numberNode(floor(it / group.interval) * group.interval) } ?: nodes.nullNode()
            }
            is AggregationGroup.DateHistogram -> filters.instant(group.field, at)
                ?.let { nodes.numberNode(bucket(it, group)) } ?: UNGROUPED
            is AggregationGroup.DatePart -> filters.instant(group.field, at)
                ?.let { nodes.numberNode(part(it, group.part, ZoneId.of(group.timeZone))) } ?: UNGROUPED
        }
    }

    private fun bucket(epochMillis: Long, group: AggregationGroup.DateHistogram): Long {
        val zone = ZoneId.of(group.timeZone)
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val start: ZonedDateTime = when (group.unit) {
            me.ahoo.wow.api.query.AggregationDateUnit.YEAR -> time.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
            me.ahoo.wow.api.query.AggregationDateUnit.QUARTER -> time.with(IsoFields.DAY_OF_QUARTER, 1)
                .truncatedTo(ChronoUnit.DAYS)
            me.ahoo.wow.api.query.AggregationDateUnit.MONTH -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            me.ahoo.wow.api.query.AggregationDateUnit.WEEK ->
                time.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS)
            me.ahoo.wow.api.query.AggregationDateUnit.DAY -> time.truncatedTo(ChronoUnit.DAYS)
            me.ahoo.wow.api.query.AggregationDateUnit.HOUR -> time.truncatedTo(ChronoUnit.HOURS)
            me.ahoo.wow.api.query.AggregationDateUnit.MINUTE -> time.truncatedTo(ChronoUnit.MINUTES)
            me.ahoo.wow.api.query.AggregationDateUnit.SECOND -> time.truncatedTo(ChronoUnit.SECONDS)
        }
        return start.toInstant().toEpochMilli()
    }

    private fun part(epochMillis: Long, part: AggregationDatePart, zone: ZoneId): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return when (part) {
            AggregationDatePart.DAY_OF_WEEK -> time.dayOfWeek.value
            AggregationDatePart.DAY_OF_MONTH -> time.dayOfMonth
            AggregationDatePart.HOUR_OF_DAY -> time.hour
            AggregationDatePart.MONTH_OF_YEAR -> time.monthValue
        }
    }

    /** One group's row: its keys under their aliases, then each metric in declaration order. */
    private fun row(keys: List<JsonNode?>, members: List<RecordScope>): ObjectNode {
        val row = nodes.objectNode()
        query.groupBy.forEachIndexed { index, group -> row.set(group.alias, keys[index] ?: nodes.nullNode()) }
        val values = LinkedHashMap<String, Double?>()
        query.metrics.forEach { metric ->
            val selected = if (metric.filter === MatchAllFilter) {
                members
            } else {
                members.filter { filters.matches(metric.filter, it) }
            }
            val value = metric(metric, selected, values)
            row.set(metric.alias, value)
            values[metric.alias] = value.takeIf(JsonNode::isNumber)?.doubleValue()
        }
        return row
    }

    @Suppress("CyclomaticComplexMethod")
    private fun metric(metric: AggregationMetric, members: List<RecordScope>, values: Map<String, Double?>): JsonNode =
        when (metric) {
            is AggregationMetric.Count -> nodes.numberNode(members.size.toLong())
            is AggregationMetric.Numeric -> numeric(
                metric.function,
                members.mapNotNull { filters.expression(metric.expression, it) }
            )
            is AggregationMetric.Any -> members.firstNotNullOfOrNull { scope ->
                scope.values(admitted.field(metric.field)).firstOrNull()?.takeUnless(JsonNode::isNull)
            } ?: nodes.nullNode()
            is AggregationMetric.DistinctCount -> nodes.numberNode(distinct(metric, members).toLong())
            is AggregationMetric.Percentile -> {
                val sorted = members.mapNotNull { filters.expression(metric.expression, it) }.sorted()
                if (sorted.isEmpty()) {
                    nodes.nullNode()
                } else {
                    // The nearest rank: the least value with at least the percentile of the values at or below it.
                    val rank = ceil(metric.percentile / PERCENT * sorted.size).toInt().coerceIn(1, sorted.size)
                    nodes.numberNode(sorted[rank - 1])
                }
            }
            is AggregationMetric.Derived -> derived(metric.expression, values)?.let(nodes::numberNode) ?: nodes.nullNode()
            is AggregationMetric.Edge -> edge(metric, members)
        }

    private fun distinct(metric: AggregationMetric.DistinctCount, members: List<RecordScope>): Int {
        val expression = metric.expression
        if (expression !is me.ahoo.wow.api.query.AggregationExpression.Field) {
            return members.mapNotNull { filters.expression(expression, it) }.distinct().size
        }
        val values = members.flatMap { it.values(admitted.field(expression.field)).flattened() }.filterNot { it.isNull }
        val seen = mutableListOf<JsonNode>()
        values.forEach { value -> if (seen.none { it.sameValue(value) }) seen.add(value) }
        return seen.size
    }

    private fun numeric(function: AggregationFunction, values: List<Double>): JsonNode {
        if (values.isEmpty()) return nodes.nullNode()
        val mean = values.average()
        val result = when (function) {
            AggregationFunction.SUM -> values.sum()
            AggregationFunction.AVG -> mean
            AggregationFunction.MIN -> values.min()
            AggregationFunction.MAX -> values.max()
            AggregationFunction.VARIANCE -> values.sumOf { (it - mean) * (it - mean) } / values.size
            AggregationFunction.STDDEV -> sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        return nodes.numberNode(result)
    }

    private fun derived(expression: DerivedExpression, values: Map<String, Double?>): Double? = when (expression) {
        is DerivedExpression.MetricRef -> values[expression.metric]
        is DerivedExpression.Constant -> expression.value
        is DerivedExpression.Binary -> {
            val left = derived(expression.left, values)
            val right = derived(expression.right, values)
            if (left == null || right == null) {
                null
            } else {
                when (expression.operator) {
                    AggregationExpressionOperator.ADD -> left + right
                    AggregationExpressionOperator.SUBTRACT -> left - right
                    AggregationExpressionOperator.MULTIPLY -> left * right
                    AggregationExpressionOperator.DIVIDE -> if (right == 0.0) null else left / right
                }?.takeIf { it.isFinite() }
            }
        }
        is DerivedExpression.Window -> throw QueryExecutionException("Windows are computed by the core.")
    }

    /** FIRST / LAST: the value on the member with the least / greatest order value; members without one are skipped. */
    private fun edge(metric: AggregationMetric.Edge, members: List<RecordScope>): JsonNode {
        val orderBy = admitted.field(checkNotNull(metric.orderBy) { "Admission names every FIRST / LAST orderBy." })
        val ordered = members.mapNotNull { scope ->
            scope.values(orderBy).singleOrNull()?.takeUnless(JsonNode::isNull)?.let { it to scope }
        }.sortedWith { left, right -> orderOf(left.first, right.first) }
        val chosen = (if (metric is AggregationMetric.First) ordered.firstOrNull() else ordered.lastOrNull())?.second
            ?: return nodes.nullNode()
        return chosen.values(admitted.field(metric.field)).singleOrNull() ?: nodes.nullNode()
    }

    private fun comparator(sort: List<Sort>): Comparator<ObjectNode> = Comparator { left, right ->
        sort.firstNotNullOfOrNull { order ->
            val comparison = orderOf(left.get(order.field.path), right.get(order.field.path))
            (if (order.direction == Sort.Direction.ASC) comparison else -comparison).takeIf { it != 0 }
        } ?: 0
    }

    private companion object {
        const val PERCENT = 100.0

        /** The key of a scope that joins no group: a date group of a record without the instant. */
        val UNGROUPED: JsonNode = nodes.missingNode()
    }
}
