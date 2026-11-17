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

package me.ahoo.wow.query.backend

import me.ahoo.wow.query.schema.Temporal
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
 * The identity and the order of JSON values wherever the core compares them itself (the in-memory reference backend,
 * the residual windows and Top-N): a number by its exact value, whatever its JSON width (an int, a long, a big
 * integer, a double or a decimal); a string by its characters, unless its field reads it as a date ([Temporal.Date]),
 * when it is its instant; a boolean false before true. Each comparison takes the field's [Temporal], or the alias's
 * that carries a field's value ([outputReadings][me.ahoo.wow.query.aggregation.outputReadings]): no string is read as
 * a date unless its field says so.
 */

/** This number's exact value. */
internal fun JsonNode.decimal(): BigDecimal = if (isIntegralNumber) BigDecimal(bigIntegerValue()) else decimalValue()

/** The order of two values of one kind (numbers, strings, booleans), or `null` when they do not compare. */
internal fun compareScalars(left: JsonNode, right: JsonNode): Int? = when {
    left.isNumber && right.isNumber -> left.decimal().compareTo(right.decimal())
    left.isString && right.isString -> left.stringValue().compareTo(right.stringValue())
    left.isBoolean && right.isBoolean -> left.booleanValue().compareTo(right.booleanValue())
    else -> null
}

/**
 * A total order over values read as [temporal] says ([orderKey]): a missing value and `null` first, then numbers,
 * strings and booleans, each by value, then anything else by its JSON text; values of different kinds by kind, so a
 * mixed set never fails.
 */
internal fun orderOf(left: JsonNode?, right: JsonNode?, temporal: Temporal?): Int {
    if (temporal != null) return orderOf(left?.orderKey(temporal), right?.orderKey(temporal), null)
    val leftRank = left.kindRank()
    val rightRank = right.kindRank()
    if (leftRank != rightRank) return leftRank.compareTo(rightRank)
    if (left == null || right == null) return 0
    return compareScalars(left, right) ?: left.toString().compareTo(right.toString())
}

/**
 * A key equal for values equal by value, read as [temporal] says ([orderKey]), to group, partition or deduplicate
 * them: a number's exact value without trailing zeros (`1`, `1L` and `1.0` are one key), a date its instant
 * (`…T00:00:00Z` and `…T01:00:00+01:00` are one key), any other value itself.
 */
internal fun JsonNode?.valueKey(temporal: Temporal?): Any? {
    val value = this?.orderKey(temporal)
    return if (value != null && value.isNumber) value.decimal().stripTrailingZeros() else value
}

/**
 * What a value compares by: a date string ([Temporal.Date]) by its instant, as an exact number of seconds, so
 * `…T00:00:00Z`, `…T00:00:00.001Z` and `…T01:00:00+01:00` compare by time and not by text; any other value itself. A
 * string that is not a date stays a string, after every date.
 */
internal fun JsonNode.orderKey(temporal: Temporal?): JsonNode {
    if (temporal != Temporal.Date || !isString) return this
    val instant = parseInstant(stringValue()) ?: return this
    return JsonNodeFactory.instance.numberNode(
        BigDecimal.valueOf(instant.epochSecond).add(BigDecimal.valueOf(instant.nano.toLong(), NANO_SCALE)),
    )
}

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

private const val NANO_SCALE = 9

private fun JsonNode?.kindRank(): Int = when {
    this == null || isNull -> 0
    isNumber -> 1
    isString -> 2
    isBoolean -> 3
    else -> 4
}
