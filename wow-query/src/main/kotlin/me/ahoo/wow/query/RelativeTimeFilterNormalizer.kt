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
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FieldType
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import tools.jackson.databind.node.JsonNodeFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

internal class RelativeTimeFilterNormalizer(
    private val defaultZoneId: ZoneId,
) {
    @Suppress("CyclomaticComplexMethod")
    fun normalize(expression: FilterExpression, now: Instant): FilterExpression = when (expression) {
        is AndFilter -> AndFilter(expression.operands.map { normalize(it, now) })
        is OrFilter -> OrFilter(expression.operands.map { normalize(it, now) })
        is NorFilter -> NorFilter(expression.operands.map { normalize(it, now) })
        is ElementMatchFilter -> ElementMatchFilter(expression.field, normalize(expression.predicate, now))
        is YesterdayFilter -> expression.dayRange(now, -1)
        is TodayFilter -> expression.dayRange(now, 0)
        is TomorrowFilter -> expression.dayRange(now, 1)
        is LastWeekFilter -> expression.weekRange(now, -1)
        is ThisWeekFilter -> expression.weekRange(now, 0)
        is NextWeekFilter -> expression.weekRange(now, 1)
        is LastMonthFilter -> expression.monthRange(now, -1)
        is ThisMonthFilter -> expression.monthRange(now, 0)
        is NextMonthFilter -> expression.monthRange(now, 1)
        is LastYearFilter -> expression.yearRange(now, -1)
        is ThisYearFilter -> expression.yearRange(now, 0)
        is NextYearFilter -> expression.yearRange(now, 1)
        is BeforeTodayFilter -> LessThanFilter(
            expression.field,
            instantNode(
                expression.field,
                today(now, expression.zoneId).atTime(LocalTime.parse(expression.time)),
                zone(expression.zoneId),
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
            LessThanFilter(expression.field, instantNode(expression.field, end, zone(expression.zoneId)))
        }

        else -> expression
    }

    private fun RelativeTimeFilter.dayRange(now: Instant, offset: Long): FilterExpression =
        range(
            field,
            today(now, zoneId).plusDays(offset).atStartOfDay(),
            today(now, zoneId).plusDays(offset + 1).atStartOfDay(),
            zone(zoneId),
        )

    private fun RelativeTimeFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun RelativeTimeFilter.monthRange(now: Instant, offset: Long): FilterExpression =
        monthRange(field, today(now, zoneId), offset, zone(zoneId))

    private fun RelativeTimeFilter.yearRange(now: Instant, offset: Long): FilterExpression {
        val start = today(now, zoneId).withDayOfYear(1).plusYears(offset)
        return range(field, start.atStartOfDay(), start.plusYears(1).atStartOfDay(), zone(zoneId))
    }

    private fun weekRange(
        field: LogicalField,
        today: LocalDate,
        offset: Long,
        zoneId: ZoneId,
    ): FilterExpression {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
        return range(field, start.atStartOfDay(), start.plusWeeks(1).atStartOfDay(), zoneId)
    }

    private fun monthRange(
        field: LogicalField,
        today: LocalDate,
        offset: Long,
        zoneId: ZoneId,
    ): FilterExpression {
        val start = today.withDayOfMonth(1).plusMonths(offset)
        return range(field, start.atStartOfDay(), start.plusMonths(1).atStartOfDay(), zoneId)
    }

    private fun range(
        field: LogicalField,
        start: LocalDateTime,
        end: LocalDateTime,
        zoneId: ZoneId,
    ): FilterExpression = AndFilter(
        listOf(
            GreaterThanOrEqualFilter(field, instantNode(field, start, zoneId)),
            LessThanFilter(field, instantNode(field, end, zoneId)),
        ),
    )

    private fun instantNode(field: LogicalField, dateTime: LocalDateTime, zoneId: ZoneId) =
        when (val type = field.temporalTypeOrDefault()) {
            FieldType.Temporal.Date -> JsonNodeFactory.instance.pojoNode(
                Instant.ofEpochMilli(dateTime.atZone(zoneId).toInstant().toEpochMilli()),
            )

            is FieldType.Temporal.NumericEpoch -> dateTime.atZone(zoneId).toInstant().let { instant ->
                JsonNodeFactory.instance.numberNode(
                    Math.addExact(
                        type.timeUnit.convert(instant.epochSecond, TimeUnit.SECONDS),
                        type.timeUnit.convert(instant.nano.toLong(), TimeUnit.NANOSECONDS),
                    ),
                )
            }

            is FieldType.Temporal.FormattedString ->
                JsonNodeFactory.instance.stringNode(type.formatter.format(dateTime.atZone(zoneId)))
        }

    private fun today(now: Instant, zoneId: String?): LocalDate = now.atZone(zone(zoneId)).toLocalDate()

    private fun zone(zoneId: String?): ZoneId = zoneId?.let(ZoneId::of) ?: defaultZoneId
}
