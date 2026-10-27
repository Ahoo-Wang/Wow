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

package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.childFilters
import me.ahoo.wow.api.query.fields
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.resolve.integralLong
import me.ahoo.wow.query.schema.Temporal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalUnit
import java.util.concurrent.TimeUnit

/*
 * The earlier periods PREVIOUS windows read (aggregation window design §3.2): each distinct step moves the bucket
 * keys and the filter's range of its date group back on the group's local wall clock, the timeline the dense grid
 * walks, so a shifted bucket key and a shifted range bound land on the same grid.
 */

/** One distinct step of the PREVIOUS windows of a query: the date group it steps along, and how far. */
internal data class PreviousStep(
    val along: AggregationGroup.DateHistogram,
    val shift: Int,
    val period: AggregationDateUnit?,
) {
    private val zone: ZoneId = ZoneId.of(along.timeZone)
    private val grid = DenseDateGrid(along.unit, zone)
    private val unit: TemporalUnit = (period ?: along.unit).temporalUnit()

    val field: QueryField
        get() = checkNotNull(along.field)

    /** The key of the bucket this step reaches from the bucket [key]. */
    fun previousKey(key: Long): Long = if (period == null) {
        grid.keyOf(grid.indexOf(key) - shift)
    } else {
        grid.keyOf(grid.indexOf(back(Instant.ofEpochMilli(key)).toEpochMilli()))
    }

    private fun back(instant: Instant): Instant =
        instant.atZone(zone).toLocalDateTime().minus(shift.toLong(), unit).atZone(zone).toInstant()

    /**
     * [filter] with every condition on [field] moved back by this step, or `null` when it cannot be: the field is
     * conditioned outside the top-level AND, or by a value that is not an instant [temporal] encodes.
     */
    fun shift(filter: FilterExpression, temporal: Temporal?): FilterExpression? {
        val operands = if (filter is AndFilter) filter.operands else listOf(filter)
        val shifted = operands.map { operand ->
            when {
                operand is FieldPredicate && operand.field == field -> shift(operand, temporal) ?: return null
                operand.mentions(field) -> return null
                else -> operand
            }
        }
        return if (filter is AndFilter) AndFilter(shifted) else shifted.single()
    }

    @Suppress("CyclomaticComplexMethod") // One exhaustive table: each operator either moves its values or keeps none.
    private fun shift(predicate: FieldPredicate, temporal: Temporal?): FilterExpression? {
        fun Any?.back(): Any? = shiftValue(this, temporal)
        fun List<Any?>.back(): List<Any?>? = map { it.back() ?: return null }
        return when (predicate) {
            is EqualFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is NotEqualFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is GreaterThanFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is GreaterThanOrEqualFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is LessThanFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is LessThanOrEqualFilter -> predicate.value.back()?.let { predicate.copy(value = it) }
            is InFilter -> predicate.values.back()?.let { predicate.copy(values = it) }
            is NotInFilter -> predicate.values.back()?.let { predicate.copy(values = it) }
            is BetweenFilter -> {
                val lower = predicate.lowerBound.back() ?: return null
                val upper = predicate.upperBound.back() ?: return null
                predicate.copy(lowerBound = lower, upperBound = upper)
            }
            // Presence holds in every period alike.
            is IsNullFilter, is IsNotNullFilter, is ExistsFilter, is NotExistsFilter -> predicate
            else -> null
        }
    }

    /** [value] moved back by this step, in its own encoding; `null` when it is not an instant. */
    private fun shiftValue(value: Any?, temporal: Temporal?): Any? {
        val epoch = value.integralLong()
        return when {
            epoch != null -> {
                val timeUnit = (temporal as? Temporal.Epoch)?.timeUnit ?: TimeUnit.MILLISECONDS
                back(epoch.toInstant(timeUnit)).toEpoch(timeUnit)
            }
            value is String -> shiftText(value)
            else -> null
        }
    }

    /** An ISO-8601 instant, offset date-time, local date-time or date moved back, in the form it came in. */
    private fun shiftText(text: String): String? = try {
        when {
            text.endsWith("Z") && 'T' in text -> back(Instant.parse(text)).toString()
            'T' in text && (text.lastIndexOf('+') > text.indexOf('T') || text.lastIndexOf('-') > text.indexOf('T')) -> {
                val moment = OffsetDateTime.parse(text)
                back(moment.toInstant()).atOffset(moment.offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            'T' in text -> LocalDateTime.parse(text).minus(shift.toLong(), unit)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            else -> LocalDate.parse(text).atStartOfDay().minus(shift.toLong(), unit).toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
    } catch (_: DateTimeException) {
        null
    }

    companion object {
        /** The step of [window], a PREVIOUS window of [query]. */
        fun of(query: AggregationQuery, window: DerivedExpression.Previous): PreviousStep = PreviousStep(
            query.groupBy.first { it.alias == window.along } as AggregationGroup.DateHistogram,
            window.shift,
            window.period,
        )

        /** The distinct steps of the PREVIOUS windows of [query]. */
        fun of(query: AggregationQuery): List<PreviousStep> =
            query.windows.filterIsInstance<DerivedExpression.Previous>().map { of(query, it) }.distinct()
    }
}

/** Whether this filter conditions [field] anywhere, an ELEMENT_MATCH over a container of it included. */
private fun FilterExpression.mentions(field: QueryField): Boolean = when (this) {
    is FieldPredicate -> this.field == field
    is ElementMatchFilter -> this.field == field || field.path.startsWith("${this.field.path}.")
    is ExpressionFilter -> field in expression.fields
    is SearchFilter -> field in fields
    else -> childFilters().any { it.mentions(field) }
}

private fun AggregationDateUnit.temporalUnit(): TemporalUnit = when (this) {
    AggregationDateUnit.YEAR -> ChronoUnit.YEARS
    AggregationDateUnit.QUARTER -> IsoFields.QUARTER_YEARS
    AggregationDateUnit.MONTH -> ChronoUnit.MONTHS
    AggregationDateUnit.WEEK -> ChronoUnit.WEEKS
    AggregationDateUnit.DAY -> ChronoUnit.DAYS
    AggregationDateUnit.HOUR -> ChronoUnit.HOURS
    AggregationDateUnit.MINUTE -> ChronoUnit.MINUTES
    AggregationDateUnit.SECOND -> ChronoUnit.SECONDS
}

private fun Long.toInstant(unit: TimeUnit): Instant {
    val seconds = unit.toSeconds(this)
    return Instant.ofEpochSecond(seconds, unit.toNanos(this - unit.convert(seconds, TimeUnit.SECONDS)))
}

private fun Instant.toEpoch(unit: TimeUnit): Long =
    Math.addExact(unit.convert(epochSecond, TimeUnit.SECONDS), unit.convert(nano.toLong(), TimeUnit.NANOSECONDS))
