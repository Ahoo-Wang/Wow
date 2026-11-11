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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.spec.Lowering
import me.ahoo.wow.query.spec.spec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Lowers relative time and derived operators. A relative-time filter reads its days in its
 * [resolved zone][RelativeTimeFilter.resolvedTimeZone], UTC when it names none: never the JVM's zone, so nodes in
 * different zones answer a filter alike. Its window's bounds are written as the field stores time, its [Temporal]: the
 * filter carries no encoding of its own.
 */
@InternalWowApi
class FilterNormalizer {
    /**
     * Lowers one predicate to the operators backends implement, as its operator's
     * [lowering][me.ahoo.wow.query.spec.FilterOperatorSpec.lowering] states: a rewrite (`EQ`/`NE` of `null` to
     * `IS_NULL`/`IS_NOT_NULL`, the empty-string predicates to equality), or relative time to a range at [now], encoded
     * as [temporal], the field's encoding, stores time. Admission calls this with the encoding it resolved, so
     * lowering never looks a field up again; a relative-time filter is admitted only on a field that keeps time.
     */
    internal fun lower(expression: FilterExpression, now: Instant, temporal: Temporal?): FilterExpression =
        when (val lowering = expression.spec.lowering) {
            null -> expression
            is Lowering.Rewrite -> lowering.lower(expression)
            Lowering.RelativeTime -> {
                val relative = expression as RelativeTimeFilter
                val resolved = checkNotNull(temporal) {
                    "Admission resolves the temporal of a relative-time filter on [${relative.field.path}]."
                }
                Window(relative, now, resolved).lower()
            }
        }

    /** The window of [filter] at [now], its bounds written as [temporal] stores time. */
    private inner class Window(
        private val filter: RelativeTimeFilter,
        private val now: Instant,
        private val temporal: Temporal,
    ) {
        private val zone: ZoneId = filter.resolvedTimeZone()
        private val today: LocalDate = now.atZone(zone).toLocalDate()
        private val field: QueryField<*> = filter.field

        @Suppress("CyclomaticComplexMethod") // Exhaustive public relative-time operators share one captured instant.
        fun lower(): FilterExpression = when (filter) {
            is YesterdayFilter -> days(-1, 1)
            is TodayFilter -> days(0, 1)
            is TomorrowFilter -> days(1, 1)
            is LastWeekFilter -> weeks(-1)
            is ThisWeekFilter -> weeks(0)
            is NextWeekFilter -> weeks(1)
            is LastMonthFilter -> months(-1)
            is ThisMonthFilter -> months(0)
            is NextMonthFilter -> months(1)
            is LastYearFilter -> years(-1)
            is ThisYearFilter -> years(0)
            is NextYearFilter -> years(1)
            is BeforeNowFilter -> LessThanFilter(field, moment(now + filter.offsetDuration))
            is AfterNowFilter -> GreaterThanFilter(field, moment(now + filter.offsetDuration))
            is BeforeTodayFilter -> LessThanFilter(field, at(today.atTime(LocalTime.parse(filter.time))))
            is RecentDaysFilter -> days(1L - filter.days, filter.days.toLong())
            is EarlierDaysFilter -> LessThanFilter(field, at(today.minusDays(filter.days.toLong() - 1).atStartOfDay()))
        }

        /** The [count] days from today plus [offset]. */
        private fun days(offset: Long, count: Long): FilterExpression =
            range(today.plusDays(offset), today.plusDays(offset + count))

        private fun weeks(offset: Long): FilterExpression {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
            return range(start, start.plusWeeks(1))
        }

        private fun months(offset: Long): FilterExpression {
            val start = today.withDayOfMonth(1).plusMonths(offset)
            return range(start, start.plusMonths(1))
        }

        private fun years(offset: Long): FilterExpression {
            val start = today.withDayOfYear(1).plusYears(offset)
            return range(start, start.plusYears(1))
        }

        private fun range(start: LocalDate, end: LocalDate): FilterExpression = AndFilter(
            listOf(
                GreaterThanOrEqualFilter(field, at(start.atStartOfDay())),
                LessThanFilter(field, at(end.atStartOfDay())),
            ),
        )

        private fun at(dateTime: LocalDateTime): Any = encoded(dateTime.atZone(zone))

        private fun moment(moment: Instant): Any = encoded(moment.atZone(zone))

        /** [moment] as the field stores time: an epoch in its unit (a date, in milliseconds), or text in its pattern. */
        private fun encoded(moment: ZonedDateTime): Any = when (temporal) {
            is Temporal.Epoch -> epoch(moment.toInstant(), temporal.timeUnit)
            is Temporal.Formatted -> temporal.format(moment)
            Temporal.Date -> epoch(moment.toInstant(), TimeUnit.MILLISECONDS)
        }

        private fun epoch(instant: Instant, unit: TimeUnit): Long = Math.addExact(
            unit.convert(instant.epochSecond, TimeUnit.SECONDS),
            unit.convert(instant.nano.toLong(), TimeUnit.NANOSECONDS),
        )
    }

    private fun FilterExpression.isConstant(): Boolean = this === MatchAllFilter || this === MatchNoneFilter

    internal fun simplifyAnd(operands: List<FilterExpression>): FilterExpression {
        // Already simple (no constant, no nested AND): the common case keeps the list it was given.
        if (operands.size > 1 && operands.none { it.isConstant() || it is AndFilter }) {
            return AndFilter(operands)
        }
        val flattened = ArrayList<FilterExpression>(operands.size)
        operands.forEach { operand ->
            when {
                operand === MatchNoneFilter -> return MatchNoneFilter
                operand === MatchAllFilter -> Unit
                operand is AndFilter -> flattened.addAll(operand.operands)
                else -> flattened += operand
            }
        }
        return when (flattened.size) {
            0 -> MatchAllFilter
            1 -> flattened.first()
            else -> AndFilter(flattened)
        }
    }

    internal fun simplifyOr(operands: List<FilterExpression>): FilterExpression {
        if (operands.size > 1 && operands.none { it.isConstant() || it is OrFilter }) {
            return OrFilter(operands)
        }
        val flattened = ArrayList<FilterExpression>(operands.size)
        operands.forEach { operand ->
            when {
                operand === MatchAllFilter -> return MatchAllFilter
                operand === MatchNoneFilter -> Unit
                operand is OrFilter -> flattened.addAll(operand.operands)
                else -> flattened += operand
            }
        }
        return when (flattened.size) {
            0 -> MatchNoneFilter
            1 -> flattened.first()
            else -> OrFilter(flattened)
        }
    }

    internal fun simplifyNor(operands: List<FilterExpression>): FilterExpression {
        val filtered = ArrayList<FilterExpression>(operands.size)
        operands.forEach { operand ->
            when {
                operand === MatchAllFilter -> return MatchNoneFilter
                operand !== MatchNoneFilter -> filtered += operand
            }
        }
        return if (filtered.isEmpty()) MatchAllFilter else NorFilter(filtered)
    }
}
