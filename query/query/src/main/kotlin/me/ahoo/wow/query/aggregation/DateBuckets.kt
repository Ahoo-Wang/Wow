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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * The buckets of a `DATE_HISTOGRAM` of [unit] in [zone], the one statement of the rule every backend's buckets follow
 * (BA-8 N1): a bucket is the instant at which the local [unit] period containing a record begins, in that period's
 * own offset. A day, week, month, quarter or year begins at the first instant of its first local date (a midnight a
 * transition skips begins at the transition); an hour, minute or second at its local start, so the hour a fall-back
 * repeats is two buckets, and the one a spring-forward skips is none (a local start the zone skipped begins at the
 * transition, as a skipped midnight does). A bucket's key is never after its records.
 *
 * The buckets are a sequence: [next] and [previous] walk it, which a dense fill and a PREVIOUS window do, so both see
 * the buckets the histogram makes. A local period can last longer or shorter than its unit: Australia/Lord_Howe's
 * fall-back makes its local 01:00 hour last 90 minutes, and its spring-forward its 02:00 hour 30 minutes.
 */
internal class DateBuckets(private val unit: AggregationDateUnit, private val zone: ZoneId) {
    /** The start of the bucket that contains [instant]. */
    fun start(instant: Instant): Instant {
        val time = instant.atZone(zone)
        return when (unit) {
            AggregationDateUnit.YEAR -> startOf(time.toLocalDate().withDayOfYear(1))
            AggregationDateUnit.QUARTER -> startOf(time.toLocalDate().with(IsoFields.DAY_OF_QUARTER, 1))
            AggregationDateUnit.MONTH -> startOf(time.toLocalDate().withDayOfMonth(1))
            AggregationDateUnit.WEEK -> startOf(
                time.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            )
            AggregationDateUnit.DAY -> startOf(time.toLocalDate())
            AggregationDateUnit.HOUR -> wallClockStart(time, ChronoUnit.HOURS)
            AggregationDateUnit.MINUTE -> wallClockStart(time, ChronoUnit.MINUTES)
            AggregationDateUnit.SECOND -> wallClockStart(time, ChronoUnit.SECONDS)
        }
    }

    /** The key of the bucket that contains [epochMillis]: its start in epoch milliseconds. */
    fun keyOf(epochMillis: Long): Long = start(Instant.ofEpochMilli(epochMillis)).toEpochMilli()

    /** The start of the first bucket after the one that starts at [start], however long that one lasts: always later. */
    fun next(start: Instant): Instant = when (unit) {
        AggregationDateUnit.YEAR -> startOf(start.atZone(zone).toLocalDate().plusYears(1))
        AggregationDateUnit.QUARTER -> startOf(start.atZone(zone).toLocalDate().plusMonths(MONTHS_PER_QUARTER))
        AggregationDateUnit.MONTH -> startOf(start.atZone(zone).toLocalDate().plusMonths(1))
        AggregationDateUnit.WEEK -> startOf(start.atZone(zone).toLocalDate().plusWeeks(1))
        AggregationDateUnit.DAY -> startOf(start.atZone(zone).toLocalDate().plusDays(1))
        AggregationDateUnit.HOUR -> after(start, Duration.ofHours(1))
        AggregationDateUnit.MINUTE -> after(start, Duration.ofMinutes(1))
        AggregationDateUnit.SECOND -> start.plusSeconds(1)
    }

    /** The start of the bucket before the one that starts at [start]: the one holding the instant just before it. */
    fun previous(start: Instant): Instant = start(start.minusMillis(1))

    /**
     * The bucket after [start]'s for a unit of fixed [length] that a transition can lengthen or shorten: steps of
     * [length] until one lands past [start]'s bucket, then back over any shorter bucket the last step went past.
     */
    private fun after(start: Instant, length: Duration): Instant {
        var probe = start.plus(length)
        while (start(probe) <= start) probe = probe.plus(length)
        var next = start(probe)
        while (true) {
            val before = previous(next)
            if (before <= start) return next
            next = before
        }
    }

    /**
     * The start of [time]'s local [unit]: its wall-clock time truncated to the unit, in [time]'s own offset where that
     * time exists in it (so the hour a fall-back repeats is two buckets). When the truncation falls in a gap, a local
     * time the zone skipped, the unit begins at the transition's instant, the first after the gap: Africa/Monrovia's
     * 1972 change skipped 00:00 to 00:44:30, so the minute of 00:44:45 begins at 00:44:30, not later than its records.
     */
    private fun wallClockStart(time: ZonedDateTime, unit: ChronoUnit): Instant {
        val local = time.toLocalDateTime().truncatedTo(unit)
        val gap = zone.rules.getTransition(local)?.takeIf { it.isGap }
        return gap?.instant ?: ZonedDateTime.ofLocal(local, zone, time.offset).toInstant()
    }

    /** The first instant of [date] in the zone. */
    private fun startOf(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    override fun toString(): String = "DateBuckets($unit, $zone)"

    private companion object {
        const val MONTHS_PER_QUARTER = 3L
    }
}
