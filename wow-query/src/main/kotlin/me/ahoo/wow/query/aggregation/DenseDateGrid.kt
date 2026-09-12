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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.time.temporal.TemporalUnit

/**
 * Timezone-safe bucket-index arithmetic for dense date histograms.
 *
 * MongoDB `$densify` has no timezone option: month/quarter/year stepping over raw dates drifts off
 * the `$dateTrunc(timezone)` grid even for fixed offsets. Both backends therefore work in a
 * integer bucket-index space anchored at a grid-aligned local midnight (Monday for WEEK); Mongo
 * inverts indices with `$dateAdd(timezone)` and this class mirrors it with `java.time`, so the
 * grids are identical by construction.
 */
class DenseDateGrid(unit: AggregationDateUnit, private val timeZone: ZoneId) {
    private val stepUnit: TemporalUnit = when (unit) {
        AggregationDateUnit.YEAR -> ChronoUnit.YEARS
        AggregationDateUnit.QUARTER -> IsoFields.QUARTER_YEARS
        AggregationDateUnit.MONTH -> ChronoUnit.MONTHS
        AggregationDateUnit.WEEK -> ChronoUnit.WEEKS
        AggregationDateUnit.DAY -> ChronoUnit.DAYS
        AggregationDateUnit.HOUR -> ChronoUnit.HOURS
        AggregationDateUnit.MINUTE -> ChronoUnit.MINUTES
        AggregationDateUnit.SECOND -> ChronoUnit.SECONDS
    }

    val anchor: ZonedDateTime = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, timeZone).let {
        if (unit == AggregationDateUnit.WEEK) it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else it
    }

    fun indexOf(epochMillis: Long): Long =
        anchor.until(Instant.ofEpochMilli(epochMillis).atZone(timeZone), stepUnit)

    fun keyOf(index: Long): Long = anchor.plus(index, stepUnit).toInstant().toEpochMilli()

    /** Grid keys strictly between the two bucket keys, emitted in stream direction. */
    fun keysBetween(fromMillis: Long, toMillis: Long): List<Long> {
        if (fromMillis == toMillis) return emptyList()
        return if (fromMillis < toMillis) {
            val toIndex = indexOf(toMillis)
            ((indexOf(fromMillis) + 1) until toIndex).map(::keyOf)
        } else {
            val toIndex = indexOf(toMillis)
            ((indexOf(fromMillis) - 1) downTo toIndex + 1).map(::keyOf)
        }
    }
}
