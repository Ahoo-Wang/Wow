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
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
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
import me.ahoo.wow.api.query.NotEqualFilter
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
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.absoluteLogicalField
import me.ahoo.wow.query.schema.withTemporal
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

class FilterNormalizer(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val defaultZoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun normalize(expression: FilterExpression): FilterExpression = normalize(expression, clock.instant(), null, null)

    fun normalize(
        expression: FilterExpression,
        schema: QueryModelSchema,
        logicalParent: QueryField? = null,
        now: Instant = clock.instant(),
    ): FilterExpression = normalize(expression, now, schema, logicalParent)

    @Suppress("CyclomaticComplexMethod")
    private fun normalize(
        input: FilterExpression,
        now: Instant,
        schema: QueryModelSchema?,
        logicalParent: QueryField?,
    ): FilterExpression {
        val expression = if (input is RelativeTimeFilter && schema != null) {
            val value = schema.field(absoluteLogicalField(input.field, logicalParent))?.value
                ?: throw QuerySchemaValidationException("Unknown relative-time field: [${input.field}].")
            input.withTemporal(value)
        } else {
            input
        }
        return when (expression) {
            is EqualFilter -> if (expression.value.isNull) IsNullFilter(expression.field) else expression
            is NotEqualFilter -> if (expression.value.isNull) IsNotNullFilter(expression.field) else expression
            is IsEmptyStringFilter -> EqualFilter(expression.field, JsonNodeFactory.instance.stringNode(""))
            is IsNotEmptyStringFilter -> simplifyAnd(
                listOf(
                    IsNotNullFilter(expression.field),
                    NotEqualFilter(expression.field, JsonNodeFactory.instance.stringNode("")),
                ),
            )
            is AndFilter -> simplifyAnd(expression.operands.map { normalize(it, now, schema, logicalParent) })
            is OrFilter -> simplifyOr(expression.operands.map { normalize(it, now, schema, logicalParent) })
            is NorFilter -> simplifyNor(expression.operands.map { normalize(it, now, schema, logicalParent) })
            is ElementMatchFilter -> ElementMatchFilter(
                expression.field,
                normalize(expression.predicate, now, schema, absoluteLogicalField(expression.field, logicalParent)),
            )
            is RelativeTimeFilter -> relativeTime(expression, now)
            else -> expression
        }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive public relative-time operators share one captured clock instant.
    private fun relativeTime(expression: RelativeTimeFilter, now: Instant): FilterExpression = when (expression) {
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
                today(now, expression.zoneId).atTime(LocalTime.parse(expression.time)),
                zone(expression.zoneId),
                expression.resolvedDateFormatter(),
                expression.timeUnit,
            ),
        )
        is RecentDaysFilter -> {
            val today = today(now, expression.zoneId)
            range(
                expression.field,
                today.minusDays(expression.days.toLong() - 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay(),
                zone(expression.zoneId),
                expression.resolvedDateFormatter(),
                expression.timeUnit,
            )
        }

        is EarlierDaysFilter -> {
            val end = today(now, expression.zoneId).minusDays(expression.days.toLong() - 1).atStartOfDay()
            LessThanFilter(
                expression.field,
                instantNode(end, zone(expression.zoneId), expression.resolvedDateFormatter(), expression.timeUnit),
            )
        }
    }

    private fun RelativeTimeFilter.dayRange(now: Instant, offset: Long): FilterExpression =
        range(
            field,
            today(now, zoneId).plusDays(offset).atStartOfDay(),
            today(now, zoneId).plusDays(offset + 1).atStartOfDay(),
            zone(zoneId),
            resolvedDateFormatter(),
            timeUnit,
        )

    private fun RelativeTimeFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, zoneId), offset, zone(zoneId), resolvedDateFormatter(), timeUnit)

    private fun RelativeTimeFilter.monthRange(now: Instant, offset: Long): FilterExpression =
        monthRange(field, today(now, zoneId), offset, zone(zoneId), resolvedDateFormatter(), timeUnit)

    private fun RelativeTimeFilter.yearRange(now: Instant, offset: Long): FilterExpression {
        val start = today(now, zoneId).withDayOfYear(1).plusYears(offset)
        return range(
            field,
            start.atStartOfDay(),
            start.plusYears(1).atStartOfDay(),
            zone(zoneId),
            resolvedDateFormatter(),
            timeUnit,
        )
    }

    private fun weekRange(
        field: QueryField,
        today: LocalDate,
        offset: Long,
        zoneId: ZoneId,
        dateFormatter: DateTimeFormatter?,
        timeUnit: TimeUnit,
    ): FilterExpression {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
        return range(field, start.atStartOfDay(), start.plusWeeks(1).atStartOfDay(), zoneId, dateFormatter, timeUnit)
    }

    private fun monthRange(
        field: QueryField,
        today: LocalDate,
        offset: Long,
        zoneId: ZoneId,
        dateFormatter: DateTimeFormatter?,
        timeUnit: TimeUnit,
    ): FilterExpression {
        val start = today.withDayOfMonth(1).plusMonths(offset)
        return range(field, start.atStartOfDay(), start.plusMonths(1).atStartOfDay(), zoneId, dateFormatter, timeUnit)
    }

    private fun range(
        field: QueryField,
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime,
        zoneId: ZoneId,
        dateFormatter: DateTimeFormatter?,
        timeUnit: TimeUnit,
    ): FilterExpression = AndFilter(
        listOf(
            GreaterThanOrEqualFilter(field, instantNode(start, zoneId, dateFormatter, timeUnit)),
            LessThanFilter(field, instantNode(end, zoneId, dateFormatter, timeUnit)),
        ),
    )

    private fun instantNode(
        dateTime: java.time.LocalDateTime,
        zoneId: ZoneId,
        dateFormatter: DateTimeFormatter?,
        timeUnit: TimeUnit,
    ) = dateFormatter?.let {
        JsonNodeFactory.instance.stringNode(it.format(dateTime.atZone(zoneId)))
    } ?: dateTime.atZone(zoneId).toInstant().let {
        JsonNodeFactory.instance.numberNode(
            Math.addExact(
                timeUnit.convert(it.epochSecond, TimeUnit.SECONDS),
                timeUnit.convert(it.nano.toLong(), TimeUnit.NANOSECONDS),
            ),
        )
    }

    private fun today(now: Instant, zoneId: String?): LocalDate = now.atZone(zone(zoneId)).toLocalDate()

    private fun zone(zoneId: String?): ZoneId = zoneId?.let(ZoneId::of) ?: defaultZoneId

    private fun simplifyAnd(operands: List<FilterExpression>): FilterExpression {
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

    private fun simplifyOr(operands: List<FilterExpression>): FilterExpression {
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

    private fun simplifyNor(operands: List<FilterExpression>): FilterExpression {
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
