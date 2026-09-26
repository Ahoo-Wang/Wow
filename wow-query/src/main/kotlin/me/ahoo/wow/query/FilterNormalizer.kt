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

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
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
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.api.query.spec.Lowering
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.absoluteLogicalField
import me.ahoo.wow.query.schema.temporal
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

    /**
     * Normalizes every filter of an aggregation against one moment: the root filter, each element filter under its
     * element chain, and the metric filters under the innermost element. Unchanged parts keep their identity.
     */
    fun normalize(query: AggregationQuery, schema: QueryModelSchema, now: Instant = clock.instant()): AggregationQuery {
        var parent: QueryField? = null
        val elements = query.elements.map { element ->
            parent = absoluteLogicalField(element.path, parent)
            val filter = normalize(element.filter, now, schema, parent)
            if (filter === element.filter) element else element.copy(filter = filter)
        }
        val metrics = query.metrics.map { metric ->
            if (metric.filter === MatchAllFilter) {
                metric
            } else {
                metric.withFilter(normalize(metric.filter, now, schema, parent))
            }
        }
        val filter = normalize(query.filter, now, schema, null)
        return if (filter === query.filter && elements == query.elements && metrics == query.metrics) {
            query
        } else {
            query.copy(filter = filter, elements = elements, metrics = metrics)
        }
    }

    private fun AggregationMetric.withFilter(filter: FilterExpression): AggregationMetric = when {
        filter === this.filter -> this
        else -> when (this) {
            is AggregationMetric.Count -> copy(filter = filter)
            is AggregationMetric.Numeric -> copy(filter = filter)
            is AggregationMetric.Any -> copy(filter = filter)
            is AggregationMetric.DistinctCount -> copy(filter = filter)
            is AggregationMetric.Percentile -> copy(filter = filter)
            is AggregationMetric.First -> copy(filter = filter)
            is AggregationMetric.Last -> copy(filter = filter)
            is AggregationMetric.Derived -> this
        }
    }

    private fun normalize(
        input: FilterExpression,
        now: Instant,
        schema: QueryModelSchema?,
        logicalParent: QueryField?,
    ): FilterExpression = when (input) {
        is AndFilter -> simplifyAnd(input.operands.map { normalize(it, now, schema, logicalParent) })
        is OrFilter -> simplifyOr(input.operands.map { normalize(it, now, schema, logicalParent) })
        is NorFilter -> simplifyNor(input.operands.map { normalize(it, now, schema, logicalParent) })
        is ElementMatchFilter -> ElementMatchFilter(
            input.field,
            normalize(input.predicate, now, schema, absoluteLogicalField(input.field, logicalParent)),
        )
        is RelativeTimeFilter -> lower(
            input,
            now,
            schema?.let {
                val field = it.field(absoluteLogicalField(input.field, logicalParent))
                    ?: throw QueryViolation.UnknownField(absoluteLogicalField(input.field, logicalParent)).rejection()
                input.temporal(field.effective.temporal)
            },
        )
        else -> lower(input, now, null)
    }

    /**
     * Lowers one predicate to the operators backends implement, as its operator's
     * [lowering][me.ahoo.wow.api.query.spec.FilterOperatorSpec.lowering] states: a rewrite (`EQ`/`NE` of `null` to
     * `IS_NULL`/`IS_NOT_NULL`, the empty-string predicates to equality), or relative time to a range at [now], encoded
     * as [temporal] (the field's encoding, when known) stores time. Admission calls this with the encoding it
     * resolved, so lowering never looks a field up again.
     */
    internal fun lower(expression: FilterExpression, now: Instant, temporal: Temporal?): FilterExpression =
        when (val lowering = expression.spec.lowering) {
            null -> expression
            is Lowering.Rewrite -> lowering.lower(expression)
            Lowering.RelativeTime -> (expression as RelativeTimeFilter).let { relative ->
                relativeTime(temporal?.let(relative::withTemporal) ?: relative, now)
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
        is BeforeNowFilter -> LessThanFilter(expression.field, expression.momentNode(now + expression.offsetDuration))
        is AfterNowFilter -> GreaterThanFilter(expression.field, expression.momentNode(now + expression.offsetDuration))
        is BeforeTodayFilter -> LessThanFilter(
            expression.field,
            instantNode(
                today(now, expression).atTime(LocalTime.parse(expression.time)),
                expression.zone(),
                expression.resolvedDateFormatter(),
                expression.timeUnit,
            ),
        )
        is RecentDaysFilter -> {
            val today = today(now, expression)
            range(
                expression.field,
                today.minusDays(expression.days.toLong() - 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay(),
                expression.zone(),
                expression.resolvedDateFormatter(),
                expression.timeUnit,
            )
        }

        is EarlierDaysFilter -> {
            val end = today(now, expression).minusDays(expression.days.toLong() - 1).atStartOfDay()
            LessThanFilter(
                expression.field,
                instantNode(end, expression.zone(), expression.resolvedDateFormatter(), expression.timeUnit),
            )
        }
    }

    private fun RelativeTimeFilter.dayRange(now: Instant, offset: Long): FilterExpression =
        range(
            field,
            today(now, this).plusDays(offset).atStartOfDay(),
            today(now, this).plusDays(offset + 1).atStartOfDay(),
            zone(),
            resolvedDateFormatter(),
            timeUnit,
        )

    private fun RelativeTimeFilter.weekRange(now: Instant, offset: Long): FilterExpression =
        weekRange(field, today(now, this), offset, zone(), resolvedDateFormatter(), timeUnit)

    private fun RelativeTimeFilter.monthRange(now: Instant, offset: Long): FilterExpression =
        monthRange(field, today(now, this), offset, zone(), resolvedDateFormatter(), timeUnit)

    private fun RelativeTimeFilter.yearRange(now: Instant, offset: Long): FilterExpression {
        val start = today(now, this).withDayOfYear(1).plusYears(offset)
        return range(
            field,
            start.atStartOfDay(),
            start.plusYears(1).atStartOfDay(),
            zone(),
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
    ) = instantNode(dateTime.atZone(zoneId), dateFormatter, timeUnit)

    private fun instantNode(
        moment: java.time.ZonedDateTime,
        dateFormatter: DateTimeFormatter?,
        timeUnit: TimeUnit,
    ) = dateFormatter?.let {
        JsonNodeFactory.instance.stringNode(it.format(moment))
    } ?: moment.toInstant().let {
        JsonNodeFactory.instance.numberNode(
            Math.addExact(
                timeUnit.convert(it.epochSecond, TimeUnit.SECONDS),
                timeUnit.convert(it.nano.toLong(), TimeUnit.NANOSECONDS),
            ),
        )
    }

    private fun RelativeTimeFilter.zone(): ZoneId = resolvedZoneId() ?: defaultZoneId

    private fun today(now: Instant, filter: RelativeTimeFilter): LocalDate = now.atZone(filter.zone()).toLocalDate()

    /** A moment encoded like the field: epoch in its time unit, or formatted in its zone. */
    private fun RelativeTimeFilter.momentNode(moment: Instant) =
        instantNode(moment.atZone(zone()), resolvedDateFormatter(), timeUnit)

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
