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
import me.ahoo.wow.query.backend.decimal
import me.ahoo.wow.query.backend.orderOf
import me.ahoo.wow.query.schema.EPOCH_MILLIS
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.instantOf
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
internal fun JsonNode.valuesAt(field: QueryField<*>): List<JsonNode> =
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

/**
 * The sort key of a field's stored values, read as [temporal] says, as MongoDB orders them: a missing field and `null`
 * are the lowest value; an array sorts by its least element ascending, a `null` element included, and its greatest
 * descending. The key is the stored value itself; [orderKey] is what it is compared by.
 */
internal fun List<JsonNode>.sortKey(ascending: Boolean, temporal: Temporal?): JsonNode? {
    val elements = flattened()
    // A null element is the least: an array holding one sorts as null ascending.
    if (ascending && elements.any { it.isNull }) return null
    val scalars = elements.filterNot { it.isNull }
    if (scalars.isEmpty()) return null
    return scalars.reduce { best, next ->
        val order = orderOf(next, best, temporal)
        val better = if (ascending) order < 0 else order > 0
        if (better) next else best
    }
}

/** The order of two stored values of a field read as [temporal] says: [orderOf] of their [orderKey]s. */
internal fun orderOf(left: JsonNode?, right: JsonNode?, temporal: Temporal?): Int =
    orderOf(left?.orderKey(temporal), right?.orderKey(temporal))

/**
 * What a stored value orders by: a date string ([Temporal.Date]) by its instant, as an exact number of seconds, so
 * `…T00:00:00Z`, `…T00:00:00.001Z` and `…T01:00:00+01:00` order by time and not by text; any other value itself. A
 * string that is not a date stays a string, after every date.
 */
internal fun JsonNode.orderKey(temporal: Temporal?): JsonNode {
    if (temporal != Temporal.Date || !isString) return this
    val instant = parseInstant(stringValue()) ?: return this
    return nodes.numberNode(BigDecimal.valueOf(instant.epochSecond).add(BigDecimal.valueOf(instant.nano.toLong(), 9)))
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

/**
 * The instant this stored value holds, read as [temporal] says it stores time (an integer as an epoch of its unit,
 * milliseconds by default; a string as ISO-8601), exactly; `null` when it holds none.
 */
internal fun JsonNode.instant(temporal: Temporal?): Instant? = when {
    isIntegralNumber -> longOrNull()?.let { (temporal as? Temporal.Epoch ?: EPOCH_MILLIS).instantOf(it) }
    isString -> parseInstant(stringValue())
    isArray && size() == 1 -> get(0).instant(temporal)
    else -> null
}

private fun JsonNode.longOrNull(): Long? = if (canConvertToLong()) longValue() else null

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
