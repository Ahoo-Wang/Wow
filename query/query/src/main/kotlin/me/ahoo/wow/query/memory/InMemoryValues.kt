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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/*
 * The value semantics of the in-memory reference backend, the canonical semantics of the filter matrix: numbers compare
 * by value whatever their JSON width, strings by their characters, a missing field and a stored null both read as
 * null, and an array field holds each of its elements.
 */

internal val nodes: JsonNodeFactory = JsonNodeFactory.instance

/** A filter operand as the tree a record stores it as. */
internal fun Any?.operandNode(): JsonNode = when (this) {
    null -> nodes.nullNode()
    is JsonNode -> this
    else -> WowJson.mapper.valueToTree(this)
}

/**
 * The values stored at [field]'s path below this node; absent segments contribute nothing, so an empty list means the
 * field is missing. An array on the way contributes each element's child, and a numeric segment on an array its
 * element at that index.
 */
internal fun JsonNode.valuesAt(field: QueryField): List<JsonNode> =
    field.path.split('.').fold(listOf(this)) { found, name -> found.flatMap { it.child(name) } }

private fun JsonNode.child(name: String): List<JsonNode> = when {
    isObject -> listOfNotNull(get(name))
    isArray -> name.toIntOrNull()?.let { listOfNotNull(get(it)) } ?: filter { it.isObject }.mapNotNull { it.get(name) }
    else -> emptyList()
}

/** The scalar this value contributes to a comparison: an array contributes each element. */
internal fun List<JsonNode>.flattened(): List<JsonNode> = flatMap { if (it.isArray) it.toList() else listOf(it) }

/** Whether two stored values are equal by value: numbers by their exact value, arrays element by element. */
internal fun JsonNode.sameValue(other: JsonNode): Boolean = when {
    isNumber && other.isNumber -> decimal().compareTo(other.decimal()) == 0
    isArray && other.isArray -> size() == other.size() && (0 until size()).all { get(it).sameValue(other.get(it)) }
    else -> this == other
}

/** Whether this stored value matches [operand]: equal by value, or, for an array, holding an element that is. */
internal fun JsonNode.matches(operand: JsonNode): Boolean =
    sameValue(operand) || (isArray && !operand.isArray && any { it.sameValue(operand) })

internal fun JsonNode.decimal(): BigDecimal = if (isIntegralNumber) BigDecimal(bigIntegerValue()) else decimalValue()

/** The order of two values of one kind (numbers, strings, booleans), or `null` when they do not compare. */
internal fun compareScalars(left: JsonNode, right: JsonNode): Int? = when {
    left.isNumber && right.isNumber -> left.decimal().compareTo(right.decimal())
    left.isString && right.isString -> left.stringValue().compareTo(right.stringValue())
    left.isBoolean && right.isBoolean -> left.booleanValue().compareTo(right.booleanValue())
    else -> null
}

/**
 * The sort key of a field's stored values, as MongoDB orders them: a missing field and `null` are the lowest value;
 * an array sorts by its least element ascending and its greatest descending.
 */
internal fun List<JsonNode>.sortKey(ascending: Boolean): JsonNode? {
    val scalars = flattened().filterNot { it.isNull }
    if (scalars.isEmpty()) return null
    return scalars.reduce { best, next ->
        val order = orderOf(next, best)
        val better = if (ascending) order < 0 else order > 0
        if (better) next else best
    }
}

/** A total order over sort keys: `null` first, then numbers, strings and booleans, each by value. */
internal fun orderOf(left: JsonNode?, right: JsonNode?): Int {
    val leftRank = left.kindRank()
    val rightRank = right.kindRank()
    if (leftRank != rightRank) return leftRank.compareTo(rightRank)
    if (left == null || right == null) return 0
    return compareScalars(left, right) ?: left.toString().compareTo(right.toString())
}

private fun JsonNode?.kindRank(): Int = when {
    this == null || isNull -> 0
    isNumber -> 1
    isString -> 2
    isBoolean -> 3
    else -> 4
}

/** A sort key as a cursor position value: a JSON scalar the core's codec encodes. */
internal fun JsonNode?.positionValue(): Any? = when {
    this == null || isNull -> null
    isIntegralNumber && canConvertToLong() -> longValue()
    isNumber -> doubleValue()
    isString -> stringValue()
    isBoolean -> booleanValue()
    else -> toString()
}

/** A cursor position value back as a sort key. */
internal fun Any?.positionNode(): JsonNode? = when (this) {
    null -> null
    is Long -> nodes.numberNode(this)
    is Int -> nodes.numberNode(this.toLong())
    is Double -> nodes.numberNode(this)
    is String -> nodes.stringNode(this)
    is Boolean -> nodes.booleanNode(this)
    else -> operandNode()
}

/** This value as a number, when it is one or a singleton array of one; `null` otherwise. */
internal fun JsonNode.numberOrNull(): Double? = when {
    isNumber -> doubleValue()
    isArray && size() == 1 && get(0).isNumber -> get(0).doubleValue()
    else -> null
}

/** This stored value as epoch milliseconds, read as [temporal] says it stores time; `null` when it is not one. */
internal fun JsonNode.epochMillis(temporal: Temporal?): Long? = when {
    isIntegralNumber -> {
        val unit = (temporal as? Temporal.Epoch)?.timeUnit ?: TimeUnit.MILLISECONDS
        Math.floorDiv(unit.toNanos(longValue()), NANOS_PER_MILLI)
    }
    isString -> parseInstant(stringValue())?.toEpochMilli()
    isArray && size() == 1 -> get(0).epochMillis(temporal)
    else -> null
}

private const val NANOS_PER_MILLI = 1_000_000L

/** An ISO-8601 instant, offset date-time, local date-time (at UTC) or date (its start, at UTC). */
internal fun parseInstant(text: String): Instant? = try {
    when {
        text.endsWith("Z") && 'T' in text -> Instant.parse(text)
        'T' in text && (text.lastIndexOf('+') > text.indexOf('T') || text.lastIndexOf('-') > text.indexOf('T')) ->
            OffsetDateTime.parse(text).toInstant()
        'T' in text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        else -> LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
} catch (_: DateTimeParseException) {
    null
}
