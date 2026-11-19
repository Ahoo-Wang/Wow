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

package me.ahoo.wow.query.resolve

import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.Temporal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit

/**
 * The operands of a date field (a `date` or `date-time` string, [me.ahoo.wow.query.schema.Temporal.Date]), as the
 * query language reads them, the same on every backend that compares dates:
 * - **The forms** ([instantOf]): a date-time with its offset, RFC 3339 (`2026-10-13T08:00:00Z`,
 *   `2026-10-13T16:00:00+08:00`, lower-case `t` and `z` too), or a date alone (`2026-10-13`), its day's first instant
 *   at UTC. Any other text, a time with no offset, epoch milliseconds as text, `2026-10`, is refused at admission.
 * - **The precision** ([lower]): a comparison holds at the operand's millisecond, so a stored instant and an operand
 *   within one millisecond are equal. Each comparison is rewritten as a range on the raw value, from the millisecond's
 *   first instant to its last, so that no backend wraps its column in a function: `eq X` is one `between` from
 *   `floor(X)` to `floor(X) + 1 ms - 1 ns`, an element of a list compares as one, `gt X` is `gte floor(X) + 1 ms`,
 *   `lte X` is `lt floor(X) + 1 ms`, `in` an `or` of such ranges, and `ne` and `not-in` their `nor`, which, as `ne`
 *   does, holds where the field is null.
 */
internal object DateOperands {
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        .toFormatter()

    /** The instant [text] names in one of the forms, or null when it is in none. */
    fun instantOf(text: String): Instant? = try {
        OffsetDateTime.parse(text, DATE_TIME).toInstant()
    } catch (_: DateTimeException) {
        try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Whether [values] are operands of a field of [temporal]: on a date field, each that is not null names an instant
     * in one of the forms; on any other field, any.
     */
    fun accepts(temporal: Temporal?, values: Iterable<Any?>): Boolean = temporal != Temporal.Date ||
        values.all { value -> value == null || value.textOrNull()?.let(::instantOf) != null }

    /** [expression], a comparison on a date field, as a range at its operands' millisecond. */
    fun lower(expression: FilterExpression): FilterExpression = equality(expression) ?: bound(expression) ?: expression

    /** An equality or a membership as its operands' millisecond ranges: an `or`, or a `nor` for its negation. */
    private fun equality(expression: FilterExpression): FilterExpression? = when (expression) {
        is EqualFilter -> expression.value.instant()?.let { within(expression.field, it) }
        is NotEqualFilter -> expression.value.instant()?.let { NorFilter(listOf(within(expression.field, it))) }
        is InFilter -> expression.values.instants()?.map { within(expression.field, it) }
            ?.let { ranges -> ranges.singleOrNull() ?: OrFilter(ranges) }
        is NotInFilter -> expression.values.instants()?.let { at -> NorFilter(at.map { within(expression.field, it) }) }
        else -> null
    }

    /** A bound as its operand's millisecond's: `gt` and `lte` past its last instant, `gte` and `lt` at its first. */
    private fun bound(expression: FilterExpression): FilterExpression? = when (expression) {
        is GreaterThanFilter -> expression.value.instant()?.let { GreaterThanOrEqualFilter(expression.field, next(it)) }
        is GreaterThanOrEqualFilter ->
            expression.value.instant()?.let { GreaterThanOrEqualFilter(expression.field, floor(it)) }
        is LessThanFilter -> expression.value.instant()?.let { LessThanFilter(expression.field, floor(it)) }
        is LessThanOrEqualFilter -> expression.value.instant()?.let { LessThanFilter(expression.field, next(it)) }
        is BetweenFilter -> between(expression)
        else -> null
    }

    private fun between(expression: BetweenFilter): FilterExpression? {
        val lower = expression.lowerBound.instant() ?: return null
        val upper = expression.upperBound.instant() ?: return null
        return BetweenFilter(expression.field, floor(lower), last(upper))
    }

    /** The instants of a millisecond: from its first, [floor], to its last, a nanosecond before the [next]. */
    private fun within(field: QueryField<*>, at: Instant): FilterExpression =
        BetweenFilter(field, floor(at), last(at))

    /**
     * The first instant of [at]'s millisecond, as a date field holds one: RFC 3339 at UTC, its fraction to the
     * nanosecond as needed. [next] is the next millisecond's, [last] the nanosecond before it.
     */
    private fun floor(at: Instant): String = text(at.truncatedTo(ChronoUnit.MILLIS))

    private fun next(at: Instant): String = text(at.truncatedTo(ChronoUnit.MILLIS).plusMillis(1))

    private fun last(at: Instant): String = text(at.truncatedTo(ChronoUnit.MILLIS).plusMillis(1).minusNanos(1))

    private fun text(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)

    private fun Any?.instant(): Instant? = this?.textOrNull()?.let(::instantOf)

    private fun List<Any?>.instants(): List<Instant>? = map { it.instant() ?: return null }

    private fun Any.textOrNull(): String? =
        this as? String ?: jsonValueOrNull()?.takeIf { it.isString }?.stringValue()
}
