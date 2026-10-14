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

package me.ahoo.wow.kernel.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.BitSet

/**
 * A cron expression of six fields (decision D198), with the syntax of Spring's `CronExpression`, evaluated without a
 * dependency: `second minute hour day-of-month month day-of-week`.
 *
 * - Each field is `*` (or `?`), a value, a range `a-b`, a step `x/n` (from `x`, a value, a range or `*`, every `n`),
 *   or a list of them separated by commas. Months may be named `JAN`–`DEC`, days of the week `SUN`–`SAT`, in any
 *   case; a day of the week is 0–7, both 0 and 7 Sunday.
 * - Day of month: `L` is the last day of the month, `L-n` the n-th day before it, `LW` its last weekday, `nW` the
 *   weekday nearest to day `n` within the month.
 * - Day of week: `dL` is the last day `d` of the month (`5L`, `FRIL`), `d#n` its n-th day `d` (`MON#1`).
 * - A day must match both day fields, as Spring reads them.
 * - The macros `@yearly` (`@annually`), `@monthly`, `@weekly`, `@daily` (`@midnight`) and `@hourly`.
 *
 * [next] reads the fields as local times of a time zone: a local time that a daylight saving gap skips runs at the
 * instant it would have been, later by the length of the gap (02:30 in a spring-forward gap runs at 03:30), unless an
 * earlier time already ran at that instant; a local time that an overlap repeats runs once, at its earlier offset.
 */
class CronExpression private constructor(
    /** The expression as given. */
    val expression: String,
    private val seconds: BitSet,
    private val minutes: BitSet,
    private val hours: BitSet,
    private val days: DayOfMonth,
    private val months: BitSet,
    private val weekdays: DayOfWeekField,
) {
    /**
     * The first time after [after] that this expression names in [zone], or null when it names none within
     * [MAX_YEARS] years (such as `0 0 0 30 2 *`).
     */
    fun next(after: Instant, zone: ZoneId): Instant? {
        var local = LocalDateTime.ofInstant(after, zone).withNano(0).plusSeconds(1)
        while (true) {
            val candidate = nextLocal(local, after.atZone(zone).year + MAX_YEARS) ?: return null
            val instant = ZonedDateTime.ofLocal(candidate, zone, null).toInstant()
            if (instant.isAfter(after)) {
                return instant
            }
            // In an overlap, the earlier offset of this local time ran already, or a gap shifted it before [after].
            local = candidate.plusSeconds(1)
        }
    }

    /** The first local time at or after [from] that this expression names, before the year [limitYear]. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One step per field, coarsest first.
    private fun nextLocal(from: LocalDateTime, limitYear: Int): LocalDateTime? {
        var time = from
        while (time.year <= limitYear) {
            val month = months.nextSetBit(time.monthValue)
            if (month == -1) {
                time = LocalDate.of(time.year + 1, 1, 1).atStartOfDay()
                continue
            }
            if (month != time.monthValue) {
                time = LocalDate.of(time.year, month, 1).atStartOfDay()
                continue
            }
            val date = time.toLocalDate()
            if (!days.matches(date) || !weekdays.matches(date)) {
                time = date.plusDays(1).atStartOfDay()
                continue
            }
            val hour = hours.nextSetBit(time.hour)
            if (hour == -1) {
                time = date.plusDays(1).atStartOfDay()
                continue
            }
            if (hour != time.hour) {
                time = date.atTime(hour, 0)
                continue
            }
            val minute = minutes.nextSetBit(time.minute)
            if (minute == -1) {
                time = date.atTime(hour, 0).plusHours(1)
                continue
            }
            if (minute != time.minute) {
                time = date.atTime(hour, minute)
                continue
            }
            val second = seconds.nextSetBit(time.second)
            if (second == -1) {
                time = date.atTime(hour, minute).plusMinutes(1)
                continue
            }
            return time.withSecond(second)
        }
        return null
    }

    override fun equals(other: Any?): Boolean = other is CronExpression && other.expression == expression

    override fun hashCode(): Int = expression.hashCode()

    override fun toString(): String = expression

    /** The day-of-month field: the days of its [bits], and its `L`, `L-n`, `LW` and `nW` entries. */
    private class DayOfMonth(val bits: BitSet, val specials: List<(LocalDate) -> Boolean>) {
        fun matches(date: LocalDate): Boolean =
            bits[date.dayOfMonth] || specials.any { it(date) }
    }

    /** The day-of-week field: the days of its [bits] (1 Monday … 7 Sunday), and its `dL` and `d#n` entries. */
    private class DayOfWeekField(val bits: BitSet, val specials: List<(LocalDate) -> Boolean>) {
        fun matches(date: LocalDate): Boolean =
            bits[date.dayOfWeek.value] || specials.any { it(date) }
    }

    companion object {
        /** How many years ahead [next] looks for a time before it gives up. */
        const val MAX_YEARS: Int = 400

        private const val FIELDS = 6
        private const val MAX_SECOND = 59
        private const val MAX_MINUTE = 59
        private const val MAX_HOUR = 23
        private const val MAX_DAY = 31
        private const val MAX_MONTH = 12
        private const val MAX_WEEKDAY = 7
        private const val SUNDAY = 7

        private val MACROS = mapOf(
            "@yearly" to "0 0 0 1 1 *",
            "@annually" to "0 0 0 1 1 *",
            "@monthly" to "0 0 0 1 * *",
            "@weekly" to "0 0 0 * * 0",
            "@daily" to "0 0 0 * * *",
            "@midnight" to "0 0 0 * * *",
            "@hourly" to "0 0 * * * *",
        )
        private val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
            .mapIndexed { index, name -> name to index + 1 }.toMap()
        private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
            .mapIndexed { index, name -> name to index }.toMap()

        /** Whether [expression] is a valid cron expression. */
        @JvmStatic
        fun isValid(expression: String): Boolean = runCatching { parse(expression) }.isSuccess

        /** Parses [expression]; an [IllegalArgumentException] names what is wrong with it. */
        @JvmStatic
        fun parse(expression: String): CronExpression {
            val text = expression.trim()
            require(text.isNotEmpty()) { "A cron expression is not blank." }
            val fields = (MACROS[text.lowercase()] ?: text).split(Regex("\\s+"))
            require(fields.size == FIELDS) {
                "Cron expression \"$expression\" has ${fields.size} fields, not six: second minute hour day-of-month " +
                    "month day-of-week."
            }
            return try {
                CronExpression(
                    expression,
                    seconds = bits(fields[0], 0, MAX_SECOND),
                    minutes = bits(fields[1], 0, MAX_MINUTE),
                    hours = bits(fields[2], 0, MAX_HOUR),
                    days = dayOfMonth(fields[3]),
                    months = bits(fields[4], 1, MAX_MONTH, MONTH_NAMES),
                    weekdays = dayOfWeek(fields[5]),
                )
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("Cron expression \"$expression\": ${invalid.message}", invalid)
            }
        }

        /** The values [field] names within [min]..[max], [names] standing for values. */
        private fun bits(field: String, min: Int, max: Int, names: Map<String, Int> = emptyMap()): BitSet {
            val bits = BitSet(max + 1)
            field.split(',').forEach { part -> addPart(bits, part, min, max, names) }
            return bits
        }

        private fun addPart(bits: BitSet, part: String, min: Int, max: Int, names: Map<String, Int>) {
            require(part.isNotEmpty()) { "an empty entry in a list" }
            val (range, step) = part.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            val every = step?.let {
                val value = it.toIntOrNull()
                require(value != null && value >= 1) { "the step \"$it\" is not a positive number" }
                value
            } ?: 1
            val (start, end) = when {
                range == "*" || range == "?" -> min to max
                '-' in range -> {
                    val (low, high) = range.split('-', limit = 2)
                    value(low, min, max, names) to value(high, min, max, names)
                }
                // `x/n` runs from x to the end of the field.
                step != null -> value(range, min, max, names) to max
                else -> value(range, min, max, names).let { it to it }
            }
            require(start <= end) { "the range $range runs backwards" }
            for (value in start..end step every) {
                bits.set(value)
            }
        }

        private fun value(text: String, min: Int, max: Int, names: Map<String, Int>): Int {
            val value = names[text.uppercase()] ?: text.toIntOrNull()
            require(value != null && value in min..max) { "\"$text\" is not a value in $min..$max" }
            return value
        }

        /** The day-of-month field, with `L`, `L-n`, `LW` and `nW`. */
        private fun dayOfMonth(field: String): DayOfMonth {
            val bits = BitSet(MAX_DAY + 1)
            val specials = ArrayList<(LocalDate) -> Boolean>()
            field.split(',').forEach { part ->
                val upper = part.uppercase()
                when {
                    upper == "L" -> specials += { date -> date.dayOfMonth == date.lengthOfMonth() }
                    upper == "LW" -> specials += { date -> date == lastWeekday(date) }
                    upper.startsWith("L-") -> {
                        val offset = value(upper.substring(2), 1, MAX_DAY - 1, emptyMap())
                        specials += { date -> date.dayOfMonth == date.lengthOfMonth() - offset }
                    }

                    upper.endsWith("W") -> {
                        val day = value(upper.dropLast(1), 1, MAX_DAY, emptyMap())
                        specials += { date -> date == nearestWeekday(date, day) }
                    }

                    else -> addPart(bits, part, 1, MAX_DAY, emptyMap())
                }
            }
            return DayOfMonth(bits, specials)
        }

        /** The day-of-week field, with `dL` and `d#n`; its bits are ISO days, 1 Monday … 7 Sunday. */
        private fun dayOfWeek(field: String): DayOfWeekField {
            val cron = BitSet(MAX_WEEKDAY + 1)
            val specials = ArrayList<(LocalDate) -> Boolean>()
            field.split(',').forEach { part ->
                val upper = part.uppercase()
                when {
                    '#' in upper -> {
                        val (day, nth) = upper.split('#', limit = 2)
                        val weekday = weekday(day)
                        val ordinal = value(nth, 1, 5, emptyMap())
                        specials += { date ->
                            date.dayOfWeek == weekday && (date.dayOfMonth - 1) / MAX_WEEKDAY + 1 == ordinal
                        }
                    }

                    upper.length > 1 && upper.endsWith("L") -> {
                        val weekday = weekday(upper.dropLast(1))
                        specials += { date ->
                            date == date.with(TemporalAdjusters.lastInMonth(weekday))
                        }
                    }

                    else -> addPart(cron, part, 0, MAX_WEEKDAY, DAY_NAMES)
                }
            }
            val iso = BitSet(MAX_WEEKDAY + 1)
            for (day in 1..MAX_WEEKDAY - 1) {
                if (cron[day]) iso.set(day)
            }
            if (cron[0] || cron[SUNDAY]) iso.set(SUNDAY)
            return DayOfWeekField(iso, specials)
        }

        private fun weekday(text: String): DayOfWeek {
            val day = value(text, 0, MAX_WEEKDAY, DAY_NAMES)
            return if (day == 0) DayOfWeek.SUNDAY else DayOfWeek.of(day)
        }

        private fun lastWeekday(date: LocalDate): LocalDate {
            val last = date.withDayOfMonth(date.lengthOfMonth())
            return when (last.dayOfWeek) {
                DayOfWeek.SATURDAY -> last.minusDays(1)
                DayOfWeek.SUNDAY -> last.minusDays(2)
                else -> last
            }
        }

        /** The weekday nearest to [day] of [date]'s month, within the month; none when the month is shorter. */
        private fun nearestWeekday(date: LocalDate, day: Int): LocalDate? {
            if (day > date.lengthOfMonth()) {
                return null
            }
            val target = date.withDayOfMonth(day)
            return when (target.dayOfWeek) {
                DayOfWeek.SATURDAY -> if (day == 1) target.plusDays(2) else target.minusDays(1)
                DayOfWeek.SUNDAY -> if (day == target.lengthOfMonth()) target.minusDays(2) else target.plusDays(1)
                else -> target
            }
        }
    }
}
