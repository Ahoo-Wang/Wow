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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The anchor MongoDB's dense date histograms count their bucket indexes from: `$densify` has no time zone, so its
 * pipeline groups by an integer index from a grid-aligned local midnight (a Monday for WEEK) and inverts it with
 * `$dateAdd(timezone)`. The buckets themselves, and the core's dense fill, are [DateBuckets]'.
 */
class DenseDateGrid(unit: AggregationDateUnit, timeZone: ZoneId) {
    val anchor: ZonedDateTime = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, timeZone).let {
        if (unit == AggregationDateUnit.WEEK) it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else it
    }
}
