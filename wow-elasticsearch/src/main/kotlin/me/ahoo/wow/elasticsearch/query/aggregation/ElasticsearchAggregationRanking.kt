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

package me.ahoo.wow.elasticsearch.query.aggregation

import me.ahoo.wow.api.query.Sort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.PriorityQueue

internal fun selectTopRows(
    rows: Iterable<ObjectNode>,
    sort: List<Sort>,
    limit: Int,
): List<ObjectNode> = BoundedTopRows(sort, limit).apply { rows.forEach(::add) }.result()

internal class BoundedTopRows(
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

private data class RankedRow(
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
