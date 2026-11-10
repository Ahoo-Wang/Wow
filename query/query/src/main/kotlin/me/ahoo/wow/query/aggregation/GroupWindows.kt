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

package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.orderOf
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/*
 * The residual window operator: the core computes the derived metrics that read a
 * window over every group the storage returned, before HAVING, sort and limit.
 */

/** The windows of this expression, in tree order. */
internal fun DerivedExpression.windows(): List<DerivedExpression.Window> = when (this) {
    is DerivedExpression.Window -> listOf(this)
    is DerivedExpression.Binary -> left.windows() + right.windows()
    is DerivedExpression.MetricRef, is DerivedExpression.Constant -> emptyList()
}

/** The windows of every derived metric of this query. */
internal val AggregationQuery.windows: List<DerivedExpression.Window>
    get() = metrics.filterIsInstance<AggregationMetric.Derived>().flatMap { it.expression.windows() }

/**
 * The derived metrics of [query] that read a window, directly or through an earlier derived metric that does: the
 * core computes them, so the storage never sees them.
 */
internal fun windowedMetrics(query: AggregationQuery): Set<String> {
    val windowed = linkedSetOf<String>()
    query.metrics.filterIsInstance<AggregationMetric.Derived>().forEach { metric ->
        if (metric.expression.readsWindow(windowed)) windowed += metric.alias
    }
    return windowed
}

private fun DerivedExpression.readsWindow(windowed: Set<String>): Boolean = when (this) {
    is DerivedExpression.Window -> true
    is DerivedExpression.MetricRef -> metric in windowed
    is DerivedExpression.Constant -> false
    is DerivedExpression.Binary -> left.readsWindow(windowed) || right.readsWindow(windowed)
}

/**
 * Computes the [windowed] derived metrics of [query] over the complete [rows][apply], in declaration order, and
 * writes each row's metrics back in declaration order.
 */
internal class GroupWindows(private val query: AggregationQuery, private val windowed: Set<String>) {
    private val groupAliases = query.groupBy.map(AggregationGroup::alias)
    private val metricAliases = query.metrics.map(AggregationMetric::alias)

    /** The distinct steps of the PREVIOUS windows: [apply] needs each one's earlier groups. */
    val previousSteps: List<PreviousStep> = PreviousStep.of(query)

    /** Computes the windows over [rows]; [earlier] holds the groups each of [previousSteps] reads. */
    fun apply(rows: List<ObjectNode>, earlier: Map<PreviousStep, List<ObjectNode>> = emptyMap()): List<ObjectNode> {
        query.metrics.forEach { metric ->
            if (metric is AggregationMetric.Derived && metric.alias in windowed) {
                val values = metric.expression.windows().distinct().associateWith { window(it, rows, earlier) }
                rows.forEachIndexed { index, row ->
                    val value = metric.expression.evaluate(row) { values.getValue(it)[index] }
                    if (value == null) row.putNull(metric.alias) else row.put(metric.alias, value)
                }
            }
        }
        return rows.map(::ordered)
    }

    /** [row] with its groups first and its metrics in declaration order, as the storage would have written it. */
    private fun ordered(row: ObjectNode): ObjectNode {
        val ordered = WowJson.mapper.createObjectNode()
        row.properties().forEach { (name, value) -> if (name !in metricAliases) ordered.set(name, value) }
        metricAliases.forEach { alias -> row.get(alias)?.let { ordered.set(alias, it) } }
        return ordered
    }

    private fun window(
        window: DerivedExpression.Window,
        rows: List<ObjectNode>,
        earlier: Map<PreviousStep, List<ObjectNode>>,
    ): Array<Double?> = when (window) {
        is DerivedExpression.Total -> total(window, rows)
        is DerivedExpression.Running -> running(window, rows)
        is DerivedExpression.Rank -> rank(window, rows)
        is DerivedExpression.Previous -> previous(window, rows, earlier)
    }

    /** Each group's [DerivedExpression.Previous.metric] in the earlier group its step reaches, every other key equal. */
    private fun previous(
        window: DerivedExpression.Previous,
        rows: List<ObjectNode>,
        earlier: Map<PreviousStep, List<ObjectNode>>,
    ): Array<Double?> {
        val step = PreviousStep.of(query, window)
        val others = groupAliases.filter { it != window.along }
        fun key(row: ObjectNode, along: Long?): List<Any?> = others.map { row[it] } + along
        val values = checkNotNull(earlier[step]) { "The earlier groups of [$step] were not read." }
            .associate { key(it, it.bucket(window.along)) to it.number(window.metric) }
        return Array(rows.size) { index ->
            val row = rows[index]
            row.bucket(window.along)?.let { values[key(row, step.previousKey(it))] }
        }
    }

    private fun total(window: DerivedExpression.Total, rows: List<ObjectNode>): Array<Double?> {
        val result = arrayOfNulls<Double>(rows.size)
        partitions(rows, window.within).forEach { partition ->
            val total = partition.mapNotNull { rows[it].number(window.metric) }.sumOrNull()
            partition.forEach { result[it] = total }
        }
        return result
    }

    private fun running(window: DerivedExpression.Running, rows: List<ObjectNode>): Array<Double?> {
        val result = arrayOfNulls<Double>(rows.size)
        val orderBy = window.order.field.path
        val tieBreak = groupAliases.filter { it != orderBy && it !in window.within }
        val comparator = Comparator<Int> { left, right ->
            orderOf(rows[left][orderBy], rows[right][orderBy])
                .let { if (window.order.direction == Sort.Direction.ASC) it else -it }
        }.thenComparator { left, right ->
            tieBreak.firstNotNullOfOrNull { alias ->
                orderOf(rows[left][alias], rows[right][alias]).takeIf { it != 0 }
            } ?: 0
        }
        partitions(rows, window.within).forEach { partition ->
            val ordered = partition.sortedWith(comparator)
            val frame = ArrayDeque<Double?>()
            ordered.forEach { index ->
                frame.addLast(rows[index].number(window.metric))
                if (window.frame != null && frame.size > window.frame!!) frame.removeFirst()
                val present = frame.filterNotNull()
                result[index] = when (window.function) {
                    RunningFunction.SUM -> present.sumOrNull()
                    RunningFunction.AVG -> present.averageOrNull()
                }
            }
        }
        return result
    }

    private fun rank(window: DerivedExpression.Rank, rows: List<ObjectNode>): Array<Double?> {
        val result = arrayOfNulls<Double>(rows.size)
        partitions(rows, window.within).forEach { partition ->
            val distinct = partition.mapNotNull { rows[it].number(window.metric) }.distinct()
            val ranked = if (window.direction == Sort.Direction.ASC) distinct.sorted() else distinct.sortedDescending()
            val ranks = ranked.withIndex().associate { (index, value) -> value to (index + 1).toDouble() }
            partition.forEach { index -> result[index] = rows[index].number(window.metric)?.let(ranks::getValue) }
        }
        return result
    }

    /** The row indexes of each partition: the rows that share their [within] keys. */
    private fun partitions(rows: List<ObjectNode>, within: List<String>): Collection<List<Int>> =
        rows.indices.groupBy { index -> within.map { rows[index][it] } }.values
}

private fun List<Double>.sumOrNull(): Double? = takeIf { it.isNotEmpty() }?.sum()

/**
 * The mean of these finite values, `null` for none. A mean of finite values is finite, so when their sum leaves the
 * double range the mean is summed from each value's share instead.
 */
private fun List<Double>.averageOrNull(): Double? {
    if (isEmpty()) return null
    val sum = sum()
    return if (sum.isFinite()) sum / size else sumOf { it / size }
}

private fun ObjectNode.number(metric: String): Double? = get(metric)?.takeIf(JsonNode::isNumber)?.doubleValue()

/** The DATE_HISTOGRAM key of [alias] in this row, or `null` when it has none. */
private fun ObjectNode.bucket(alias: String): Long? = get(alias)?.takeIf(JsonNode::isIntegralNumber)?.longValue()

/** This expression over [row], each window read from [window]: null propagates and a division by zero is `null`. */
private fun DerivedExpression.evaluate(row: ObjectNode, window: (DerivedExpression.Window) -> Double?): Double? =
    when (this) {
        is DerivedExpression.MetricRef -> row.number(metric)
        is DerivedExpression.Constant -> value
        is DerivedExpression.Window -> window(this)
        is DerivedExpression.Binary -> {
            val leftValue = left.evaluate(row, window)
            val rightValue = right.evaluate(row, window)
            if (leftValue == null || rightValue == null) {
                null
            } else {
                when (operator) {
                    AggregationExpressionOperator.ADD -> leftValue + rightValue
                    AggregationExpressionOperator.SUBTRACT -> leftValue - rightValue
                    AggregationExpressionOperator.MULTIPLY -> leftValue * rightValue
                    AggregationExpressionOperator.DIVIDE -> if (rightValue == 0.0) null else leftValue / rightValue
                }?.takeIf { it.isFinite() }
            }
        }
    }
