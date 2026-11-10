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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DateBucketsTest {
    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")

    private fun instant(text: String): Instant = Instant.parse(text)

    private fun local(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

    /** The bucket starts from the bucket of [from] up to and including [to]'s, walked forward. */
    private fun DateBuckets.walk(from: Instant, to: Instant): List<Instant> =
        generateSequence(start(from)) { next(it) }.takeWhile { it <= start(to) }.toList()

    @Test
    fun `every unit starts its bucket at the local start of its period`() {
        val at = instant("2026-05-14T15:42:17.123Z") // Thursday
        mapOf(
            AggregationDateUnit.YEAR to "2026-01-01T00:00:00Z",
            AggregationDateUnit.QUARTER to "2026-04-01T00:00:00Z",
            AggregationDateUnit.MONTH to "2026-05-01T00:00:00Z",
            AggregationDateUnit.WEEK to "2026-05-11T00:00:00Z",
            AggregationDateUnit.DAY to "2026-05-14T00:00:00Z",
            AggregationDateUnit.HOUR to "2026-05-14T15:00:00Z",
            AggregationDateUnit.MINUTE to "2026-05-14T15:42:00Z",
            AggregationDateUnit.SECOND to "2026-05-14T15:42:17Z",
        ).forEach { (unit, start) ->
            DateBuckets(unit, utc).start(at).assert().describedAs("$unit").isEqualTo(instant(start))
        }
        // A fixed offset keeps its local calendar: a Shanghai month starts at local midnight.
        DateBuckets(AggregationDateUnit.MONTH, ZoneId.of("Asia/Shanghai")).start(instant("2026-02-28T20:00:00Z"))
            .assert().isEqualTo(instant("2026-02-28T16:00:00Z"))
    }

    @Test
    fun `an instant before 1970 is in the bucket that contains it`() {
        AggregationDateUnit.entries.forEach { unit ->
            val buckets = DateBuckets(unit, utc)
            listOf("1969-12-31T23:59:59.999Z", "1969-12-31T00:00:00Z", "1969-06-15T12:34:56Z").map(::instant)
                .forEach { probe ->
                    val start = buckets.start(probe)
                    (start <= probe).assert().describedAs("$unit $probe").isTrue()
                    (buckets.next(start) > probe).assert().describedAs("$unit $probe").isTrue()
                }
        }
        DateBuckets(AggregationDateUnit.DAY, utc).keyOf(instant("1969-12-31T12:00:00Z").toEpochMilli())
            .assert().isEqualTo(instant("1969-12-31T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `next and previous walk the buckets one at a time, in every unit`() {
        AggregationDateUnit.entries.forEach { unit ->
            val buckets = DateBuckets(unit, newYork)
            var start = buckets.start(instant("2025-01-01T12:00:00Z"))
            repeat(40) {
                val next = buckets.next(start)
                (next > start).assert().describedAs("$unit $start").isTrue()
                buckets.start(next).assert().isEqualTo(next)
                buckets.previous(next).assert().describedAs("$unit $next").isEqualTo(start)
                start = next
            }
        }
        DateBuckets(AggregationDateUnit.WEEK, utc).next(instant("2026-01-05T00:00:00Z"))
            .assert().isEqualTo(instant("2026-01-12T00:00:00Z"))
        DateBuckets(AggregationDateUnit.QUARTER, utc).next(instant("2026-01-01T00:00:00Z"))
            .assert().isEqualTo(instant("2026-04-01T00:00:00Z"))
    }

    @Test
    fun `the hour a fall-back repeats is two buckets, and the one a spring-forward skips is none`() {
        val hours = DateBuckets(AggregationDateUnit.HOUR, newYork)
        // 2025-11-02: 01:30 EDT and 01:30 EST, an hour apart, each in its own 01:00.
        hours.start(instant("2025-11-02T05:30:00Z")).assert().isEqualTo(instant("2025-11-02T05:00:00Z"))
        hours.start(instant("2025-11-02T06:30:00Z")).assert().isEqualTo(instant("2025-11-02T06:00:00Z"))
        hours.walk(instant("2025-11-02T04:00:00Z"), instant("2025-11-02T07:00:00Z")).assert().containsExactly(
            instant("2025-11-02T04:00:00Z"),
            instant("2025-11-02T05:00:00Z"),
            instant("2025-11-02T06:00:00Z"),
            instant("2025-11-02T07:00:00Z"),
        )
        // 2025-03-09: 01:00 EST is followed by 03:00 EDT.
        hours.next(instant("2025-03-09T06:00:00Z")).assert().isEqualTo(instant("2025-03-09T07:00:00Z"))
        hours.previous(instant("2025-03-09T07:00:00Z")).assert().isEqualTo(instant("2025-03-09T06:00:00Z"))
        // A day of 23 hours and one of 25 are each one bucket.
        val days = DateBuckets(AggregationDateUnit.DAY, newYork)
        days.next(local(newYork, 2025, 3, 9)).assert().isEqualTo(local(newYork, 2025, 3, 10))
        days.next(local(newYork, 2025, 11, 2)).assert().isEqualTo(local(newYork, 2025, 11, 3))
    }

    @Test
    fun `a local date a zone skipped whole is no bucket`() {
        // Pacific/Apia went from 2011-12-29 23:59:59 (-10:00) to 2011-12-31 00:00 (+14:00).
        val apia = ZoneId.of("Pacific/Apia")
        val days = DateBuckets(AggregationDateUnit.DAY, apia)
        days.next(local(apia, 2011, 12, 29)).assert().isEqualTo(local(apia, 2011, 12, 31))
        days.previous(local(apia, 2011, 12, 31)).assert().isEqualTo(local(apia, 2011, 12, 29))
    }

    @Test
    fun `a half-hour offset keeps the buckets on its local hours`() {
        // Australia/Lord_Howe sits at +10:30 in July: its hours start at local :00, which is :30 in UTC.
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        val hours = DateBuckets(AggregationDateUnit.HOUR, lordHowe)
        hours.walk(local(lordHowe, 2026, 7, 2, 0), local(lordHowe, 2026, 7, 2, 2)).assert().containsExactly(
            local(lordHowe, 2026, 7, 2, 0),
            local(lordHowe, 2026, 7, 2, 1),
            local(lordHowe, 2026, 7, 2, 2),
        )
        hours.start(local(lordHowe, 2026, 7, 2, 1, 45)).assert().isEqualTo(local(lordHowe, 2026, 7, 2, 1))
    }
}
