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

import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.PriorityQueue

/*
 * Residual aggregation operators: shared pure functions the core applies after a backend that declares HAVING, top-N
 * or dense fill RESIDUAL. They work on the rows the backend emits, in its effective group order, so every backend
 * that delegates an operator gets the same result.
 */

/**
 * Evaluates HAVING against one produced row. Null fails: a missing or JSON-null metric makes every comparison false;
 * [HavingExpression.IsNull] captures exactly those rows.
 */
fun ObjectNode.matchesHaving(having: HavingExpression): Boolean = when (having) {
    is HavingExpression.And -> having.operands.all { matchesHaving(it) }
    is HavingExpression.Or -> having.operands.any { matchesHaving(it) }
    is HavingExpression.IsNull -> isNullMetric(having.metric) != having.negated
    is HavingExpression.Condition -> metricDouble(having.metric)
        ?.let { compare(it, having.operator, having.value) } == true

    is HavingExpression.Between -> metricDouble(having.metric)
        ?.let { it >= having.lower && it <= having.upper } == true

    is HavingExpression.In -> metricDouble(having.metric)
        ?.let { value -> having.values.any { it == value } } == true
}

private fun ObjectNode.isNullMetric(metric: String): Boolean {
    val value = get(metric)
    return value == null || value.isNull
}

private fun ObjectNode.metricDouble(metric: String): Double? {
    val value = get(metric) ?: return null
    if (value.isNull) return null
    return value.asDouble()
}

private fun compare(left: Double, operator: ComparisonOperator, right: Double): Boolean = when (operator) {
    ComparisonOperator.EQ -> left == right
    ComparisonOperator.NE -> left != right
    ComparisonOperator.GT -> left > right
    ComparisonOperator.GTE -> left >= right
    ComparisonOperator.LT -> left < right
    ComparisonOperator.LTE -> left <= right
}

/** Fills the empty buckets of a sole dense group; fill rows carry each metric's empty value ([EmptyAggregationValues]). */
sealed interface DenseFill {
    fun fill(rows: Flux<ObjectNode>): Flux<ObjectNode>

    companion object {
        /** The fill of [group], or `null` when it is not a dense group. */
        fun of(group: AggregationGroup, direction: Sort.Direction, metrics: List<AggregationMetric>): DenseFill? =
            when (group) {
                is AggregationGroup.DateHistogram -> DateHistogramFill(group, metrics).takeIf { group.dense }
                is AggregationGroup.DatePart -> DatePartFill(group, direction, metrics).takeIf { group.dense }
                is AggregationGroup.Terms, is AggregationGroup.Histogram -> null
            }
    }
}

private fun emptyRow(alias: String, key: Any, emptyMetrics: Map<String, Any?>): ObjectNode {
    val values = LinkedHashMap<String, Any?>(emptyMetrics.size + 1)
    values[alias] = key
    values.putAll(emptyMetrics)
    return JsonSerializer.valueToTree(values)
}

/**
 * Fills the empty buckets of a sole dense date histogram between consecutive rows, in stream direction. Fill rows
 * are generated on demand, because one gap (two SECOND buckets a year apart) can span more buckets than the heap
 * holds.
 */
class DateHistogramFill(private val group: AggregationGroup.DateHistogram, metrics: List<AggregationMetric>) :
    DenseFill {
    private val grid = DenseDateGrid(group.unit, java.time.ZoneId.of(group.timeZone))
    private val emptyMetrics = EmptyAggregationValues.values(metrics)

    override fun fill(rows: Flux<ObjectNode>): Flux<ObjectNode> = Flux.defer {
        var previous: Long? = null
        rows.concatMap(
            { row ->
                val key = row.get(group.alias)?.takeIf(JsonNode::isIntegralNumber)?.longValue()
                val gap = previous?.let { from -> key?.let { to -> gapRows(from, to) } } ?: Flux.empty()
                if (key != null) previous = key
                Flux.concat(gap, Flux.just(row))
            },
            1,
        )
    }

    private fun gapRows(fromKey: Long, toKey: Long): Flux<ObjectNode> =
        Flux.fromStream { grid.gapIndices(fromKey, toKey).mapToObj(grid::keyOf) }.map { key ->
            emptyRow(group.alias, key, emptyMetrics)
        }
}

/**
 * Completes a sole dense DATE_PART group to its whole fixed domain, in [direction] order: a key with no row gets a
 * fill row. The domain has at most 31 keys, so the rows are collected before they are merged.
 */
class DatePartFill(
    private val group: AggregationGroup.DatePart,
    private val direction: Sort.Direction,
    metrics: List<AggregationMetric>,
) : DenseFill {
    private val emptyMetrics = EmptyAggregationValues.values(metrics)

    override fun fill(rows: Flux<ObjectNode>): Flux<ObjectNode> = rows.collectList().flatMapIterable { present ->
        val byKey = present.associateBy { row -> row.get(group.alias)?.takeIf(JsonNode::isIntegralNumber)?.intValue() }
        val keys = if (direction == Sort.Direction.ASC) group.part.domain else group.part.domain.reversed()
        keys.map { key -> byKey[key] ?: emptyRow(group.alias, key, emptyMetrics) }
    }
}

/**
 * Keeps the first [limit] rows under [sort] while rows stream in, holding at most [limit] of them. Sort fields that
 * are [groupAliases] keep the order the backend emitted the groups in, since the storage's group order (dates,
 * keywords) is not necessarily the JSON order of the keys; metric fields compare by value, nulls first.
 */
class BoundedTopRows(
    sort: List<Sort>,
    private val limit: Int,
    private val groupAliases: List<String> = emptyList(),
) {
    private val groupIndexes = groupAliases.withIndex().associate { (index, alias) -> alias to index }
    private val currentGroupOrder = LongArray(groupAliases.size)
    private var previous: ObjectNode? = null
    private var sequence = 0L
    private val comparator = rankedRowComparator(sort, groupIndexes)
    private val rows = PriorityQueue(comparator.reversed())

    fun add(row: ObjectNode) {
        previous?.let { previous ->
            val firstDifference = groupAliases.indexOfFirst { previous[it] != row[it] }
            if (firstDifference >= 0) {
                currentGroupOrder.fill(sequence, firstDifference)
            }
        }
        previous = row
        val rankedRow = RankedRow(row, currentGroupOrder.copyOf())
        sequence++
        if (rows.size < limit) {
            rows += rankedRow
        } else if (comparator.compare(rankedRow, rows.peek()) < 0) {
            rows.poll()
            rows += rankedRow
        }
    }

    fun result(): List<ObjectNode> = rows.sortedWith(comparator).map(RankedRow::row)
}

/** The top [limit] of [rows] under [sort]; see [BoundedTopRows]. */
fun selectTopRows(rows: Iterable<ObjectNode>, sort: List<Sort>, limit: Int): List<ObjectNode> =
    BoundedTopRows(sort, limit).apply { rows.forEach(::add) }.result()

private class RankedRow(
    val row: ObjectNode,
    val groupOrder: LongArray,
)

private fun rankedRowComparator(
    sort: List<Sort>,
    groupIndexes: Map<String, Int>,
): Comparator<RankedRow> = Comparator { left, right ->
    sort.firstNotNullOfOrNull { field ->
        val comparison = groupIndexes[field.field.path]?.let { index ->
            left.groupOrder[index].compareTo(right.groupOrder[index])
        } ?: compareValues(left.row[field.field.path], right.row[field.field.path])
            .let { if (field.direction == Sort.Direction.ASC) it else -it }
        comparison.takeIf { it != 0 }
    } ?: 0
}

private fun compareValues(left: JsonNode?, right: JsonNode?): Int {
    val leftValue = left.toSortValue()
    val rightValue = right.toSortValue()
    return when {
        leftValue === rightValue -> 0
        leftValue == null -> -1
        rightValue == null -> 1
        leftValue is Long && rightValue is Long -> leftValue.compareTo(rightValue)
        leftValue is Number && rightValue is Number -> leftValue.toDouble().compareTo(rightValue.toDouble())
        leftValue is String && rightValue is String -> leftValue.compareTo(rightValue)
        leftValue is Boolean && rightValue is Boolean -> leftValue.compareTo(rightValue)
        else -> incomparableValues(left, right)
    }
}

private fun JsonNode?.toSortValue(): Any? = when {
    this == null || isNull -> null
    isIntegralNumber -> longValue()
    isNumber -> doubleValue()
    isString -> stringValue()
    isBoolean -> booleanValue()
    else -> this
}

private fun incomparableValues(left: JsonNode?, right: JsonNode?): Nothing =
    error(
        "Aggregation sort values must have comparable types, " +
            "but were [${left?.nodeType}] and [${right?.nodeType}].",
    )
