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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class FilterNormalizer(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val defaultZoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun normalize(expression: FilterExpression): FilterExpression {
        val structural = normalizeStructural(expression)
        val scoped = if (structural.containsDeletion()) {
            structural
        } else {
            AndFilter(listOf(DeletionFilter(DeletionState.ACTIVE), structural))
        }
        val now = clock.instant()
        return simplify(expandRelativeTime(scoped, now))
    }

    private fun normalizeStructural(expression: FilterExpression): FilterExpression = when (expression) {
        is EqualFilter -> if (expression.value.isNull) IsNullFilter(expression.field) else expression
        is NotEqualFilter -> if (expression.value.isNull) IsNotNullFilter(expression.field) else expression
        is AndFilter -> AndFilter(expression.operands.map(::normalizeStructural))
        is OrFilter -> OrFilter(expression.operands.map(::normalizeStructural))
        is NorFilter -> NorFilter(expression.operands.map(::normalizeStructural))
        is ElementMatchFilter -> ElementMatchFilter(expression.field, normalizeStructural(expression.predicate))
        else -> expression
    }

    private fun FilterExpression.containsDeletion(): Boolean = when (this) {
        is DeletionFilter -> true
        is AndFilter -> operands.any { it.containsDeletion() }
        is OrFilter -> operands.any { it.containsDeletion() }
        is NorFilter -> operands.any { it.containsDeletion() }
        is ElementMatchFilter -> predicate.containsDeletion()
        else -> false
    }

    @Suppress("CyclomaticComplexMethod")
    private fun expandRelativeTime(expression: FilterExpression, now: Instant): FilterExpression = when (expression) {
        is AndFilter -> AndFilter(expression.operands.map { expandRelativeTime(it, now) })
        is OrFilter -> OrFilter(expression.operands.map { expandRelativeTime(it, now) })
        is NorFilter -> NorFilter(expression.operands.map { expandRelativeTime(it, now) })
        is ElementMatchFilter -> ElementMatchFilter(expression.field, expandRelativeTime(expression.predicate, now))
        is TodayFilter -> expression.dayRange(now, 0)
        is TomorrowFilter -> expression.dayRange(now, 1)
        is ThisWeekFilter -> expression.weekRange(now, 0)
        is NextWeekFilter -> expression.weekRange(now, 1)
        is LastWeekFilter -> expression.weekRange(now, -1)
        is ThisMonthFilter -> expression.monthRange(now, 0)
        is LastMonthFilter -> expression.monthRange(now, -1)
        is BeforeTodayFilter -> LessThanFilter(
            expression.field,
            instantNode(
                today(now, expression.zoneId).atTime(LocalTime.parse(expression.time)),
                zone(expression.zoneId)
            ),
        )
        is RecentDaysFilter -> {
            val today = today(now, expression.zoneId)
            range(
                expression.field,
                today.minusDays(expression.days.toLong() - 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay(),
                zone(expression.zoneId),
            )
        }

        is EarlierDaysFilter -> {
            val end = today(now, expression.zoneId).minusDays(expression.days.toLong() - 1).atStartOfDay()
            LessThanFilter(expression.field, instantNode(end, zone(expression.zoneId)))
        }

        else -> expression
    }

    private fun TodayFilter.dayRange(now: Instant, days: Long): FilterExpression =
        range(
            field,
            today(now, zoneId).plusDays(days).atStartOfDay(),
            today(now, zoneId).plusDays(days + 1).atStartOfDay(),
            zone(zoneId),
        )

    private fun TomorrowFilter.dayRange(now: Instant, days: Long): FilterExpression =
        range(
            field,
            today(now, zoneId).plusDays(days).atStartOfDay(),
            today(now, zoneId).plusDays(days + 1).atStartOfDay(),
            zone(zoneId),
        )

    private fun ThisWeekFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun NextWeekFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun LastWeekFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun ThisMonthFilter.monthRange(now: Instant, offset: Long): FilterExpression =
        monthRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun LastMonthFilter.monthRange(now: Instant, offset: Long): FilterExpression =
        monthRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun weekRange(field: LogicalField, today: LocalDate, offset: Long, zoneId: ZoneId): FilterExpression {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
        return range(field, start.atStartOfDay(), start.plusWeeks(1).atStartOfDay(), zoneId)
    }

    private fun monthRange(field: LogicalField, today: LocalDate, offset: Long, zoneId: ZoneId): FilterExpression {
        val start = today.withDayOfMonth(1).plusMonths(offset)
        return range(field, start.atStartOfDay(), start.plusMonths(1).atStartOfDay(), zoneId)
    }

    private fun range(
        field: LogicalField,
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime,
        zoneId: ZoneId = defaultZoneId,
    ): FilterExpression = AndFilter(
        listOf(
            GreaterThanOrEqualFilter(field, instantNode(start, zoneId)),
            LessThanFilter(field, instantNode(end, zoneId)),
        ),
    )

    private fun instantNode(dateTime: java.time.LocalDateTime, zoneId: ZoneId = defaultZoneId) =
        JsonNodeFactory.instance.numberNode(dateTime.atZone(zoneId).toInstant().toEpochMilli())

    private fun today(now: Instant, zoneId: String?): LocalDate = now.atZone(zone(zoneId)).toLocalDate()

    private fun zone(zoneId: String?): ZoneId = zoneId?.let(ZoneId::of) ?: defaultZoneId

    private fun simplify(expression: FilterExpression): FilterExpression = when (expression) {
        is AndFilter -> simplifyAnd(expression.operands.map(::simplify))
        is OrFilter -> simplifyOr(expression.operands.map(::simplify))
        is NorFilter -> simplifyNor(expression.operands.map(::simplify))
        is ElementMatchFilter -> ElementMatchFilter(expression.field, simplify(expression.predicate))
        else -> expression
    }

    private fun simplifyAnd(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchNoneFilter }) return MatchNoneFilter
        val flattened = operands.flatMap { if (it is AndFilter) it.operands else listOf(it) }
            .filterNot { it === MatchAllFilter }
        return when (flattened.size) {
            0 -> MatchAllFilter
            1 -> flattened.first()
            else -> AndFilter(flattened)
        }
    }

    private fun simplifyOr(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchAllFilter }) return MatchAllFilter
        val flattened = operands.flatMap { if (it is OrFilter) it.operands else listOf(it) }
            .filterNot { it === MatchNoneFilter }
        return when (flattened.size) {
            0 -> MatchNoneFilter
            1 -> flattened.first()
            else -> OrFilter(flattened)
        }
    }

    private fun simplifyNor(operands: List<FilterExpression>): FilterExpression {
        if (operands.any { it === MatchAllFilter }) return MatchNoneFilter
        val filtered = operands.filterNot { it === MatchNoneFilter }
        return if (filtered.isEmpty()) MatchAllFilter else NorFilter(filtered)
    }
}
