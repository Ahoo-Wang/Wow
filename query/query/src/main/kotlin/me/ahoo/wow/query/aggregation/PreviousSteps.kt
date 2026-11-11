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
import me.ahoo.wow.query.schema.EPOCH_MILLIS
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.epochOf
import me.ahoo.wow.query.schema.instantOf
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalUnit

/*
 * The earlier periods PREVIOUS windows read: each distinct step moves the bucket keys and the filter's range of its
 * date group back together. A step along the buckets walks the histogram's own sequence of buckets ([DateBuckets]),
 * so across a daylight-saving transition it reaches the bucket before, never its own; a step of a period moves back on
 * the local wall clock (the same hour of the day before).
 */

/** One distinct step of the PREVIOUS windows of a query: the date group it steps along, and how far. */
internal data class PreviousStep(
    val along: AggregationGroup.DateHistogram,
    val shift: Int,
    val period: AggregationDateUnit?,
) {
    private val zone: ZoneId = ZoneId.of(along.effectiveTimeZone())
    private val buckets = DateBuckets(along.unit, zone)
    private val unit: TemporalUnit = (period ?: along.unit).temporalUnit()

    val field: QueryField<*>
        get() = checkNotNull(along.field)

    /**
     * The key of the bucket this step reaches from the bucket [key]: [shift] buckets before it, or the bucket that
     * holds the same local time [shift] [period]s before.
     */
    fun previousKey(key: Long): Long {
        val start = Instant.ofEpochMilli(key)
        val reached = if (period == null) walkBack(start) else buckets.start(back(start))
        return reached.toEpochMilli()
    }

    /** The start of the bucket [shift] buckets before the bucket that starts at [start]. */
    private fun walkBack(start: Instant): Instant = (1..shift).fold(start) { at, _ -> buckets.previous(at) }

    /** [instant] moved back by this step; `null` when that leaves the instants a date-time holds. */
    private fun movedBack(instant: Instant): Instant? = try {
        back(instant)
    } catch (_: DateTimeException) {
        null
    }

    /**
     * [instant] moved back by this step. Along the buckets: to the same wall-clock distance from the start of the bucket
     * [shift] buckets before its own, so a range bound moves as the bucket keys do; by a [period]: to the same local
     * time [shift] periods before (a time a transition skips resolves forward, a repeated one to its earlier offset).
     */
    private fun back(instant: Instant): Instant {
        if (period != null) {
            return instant.atZone(zone).toLocalDateTime().minus(shift.toLong(), unit).atZone(zone).toInstant()
        }
        val own = buckets.start(instant)
        val reached = walkBack(own).atZone(zone)
        val distance = Duration.between(own.atZone(zone).toLocalDateTime(), instant.atZone(zone).toLocalDateTime())
        return ZonedDateTime.ofLocal(reached.toLocalDateTime().plus(distance), zone, reached.offset).toInstant()
    }

    /**
     * [filter] with every condition on [field] moved back by this step, or `null` when it cannot be: the field is
     * conditioned outside the top-level AND, or by a value that is not an instant [temporal] encodes or that moved back
     * leaves the instants it can encode. Admission refuses such a query ([QueryViolation.PreviousUnshiftable]).
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

    /**
     * [value] moved back by this step, in its own encoding; `null` when it is not an instant, or moved back is no
     * instant (before [Instant.MIN]) or no epoch of its unit (below a Long).
     */
    private fun shiftValue(value: Any?, temporal: Temporal?): Any? {
        val epoch = value.integralLong()
        return when {
            epoch != null -> {
                val unit = temporal as? Temporal.Epoch ?: EPOCH_MILLIS
                unit.instantOf(epoch)?.let(::movedBack)?.let(unit::epochOf)
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
private fun FilterExpression.mentions(field: QueryField<*>): Boolean = when (this) {
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
