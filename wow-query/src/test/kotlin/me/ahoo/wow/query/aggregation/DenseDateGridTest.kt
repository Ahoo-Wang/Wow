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
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DenseDateGridTest {
    private val utc = ZoneId.of("UTC")

    private fun millis(dateTime: String): Long = Instant.parse(dateTime).toEpochMilli()

    @Test
    fun `utc day grid should step whole days`() {
        val grid = DenseDateGrid(AggregationDateUnit.DAY, utc)
        // 20455 = ChronoUnit.DAYS.between(1970-01-01T00:00:00Z, 2026-01-02T00:00:00Z), verified programmatically.
        grid.indexOf(millis("2026-01-02T00:00:00Z")).assert().isEqualTo(20455L)
        grid.keyOf(20455L).assert().isEqualTo(millis("2026-01-02T00:00:00Z"))
        grid.keysBetween(millis("2026-01-01T00:00:00Z"), millis("2026-01-04T00:00:00Z"))
            .assert().containsExactly(millis("2026-01-02T00:00:00Z"), millis("2026-01-03T00:00:00Z"))
    }

    @Test
    fun `descending gap should emit keys in stream direction`() {
        val grid = DenseDateGrid(AggregationDateUnit.DAY, utc)
        grid.keysBetween(millis("2026-01-04T00:00:00Z"), millis("2026-01-01T00:00:00Z"))
            .assert().containsExactly(millis("2026-01-03T00:00:00Z"), millis("2026-01-02T00:00:00Z"))
    }

    @Test
    fun `adjacent buckets should have no gap keys`() {
        val grid = DenseDateGrid(AggregationDateUnit.SECOND, utc)
        grid.keysBetween(millis("2026-01-01T00:00:00Z"), millis("2026-01-01T00:00:01Z"))
            .assert().isEmpty()
    }

    @Test
    fun `equal instants should yield an empty gap stream`() {
        // The same bucket on both ends is not a gap, in either stream direction.
        val grid = DenseDateGrid(AggregationDateUnit.DAY, utc)
        val day = millis("2026-01-02T00:00:00Z")
        grid.keysBetween(day, day).assert().isEmpty()
        grid.gapIndices(day, day).count().assert().isZero()
    }

    @Test
    fun `shanghai month grid should align local month starts`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val grid = DenseDateGrid(AggregationDateUnit.MONTH, zone)
        val january = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val march = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val february = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        grid.keysBetween(january, march).assert().containsExactly(february)
        // A fixed +8 offset makes raw UTC calendar stepping drift (31 days + 1 month -> the 28th),
        // so local calendar arithmetic must keep the grid aligned.
        grid.keyOf(grid.indexOf(february)).assert().isEqualTo(february)
    }

    @Test
    fun `dst zone month grid should keep local midnight boundaries`() {
        val zone = ZoneId.of("America/New_York")
        val grid = DenseDateGrid(AggregationDateUnit.MONTH, zone)
        val january = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val april = ZonedDateTime.of(2026, 4, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val february = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val march = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        grid.keysBetween(january, april).assert().containsExactly(february, march)
    }

    @Test
    fun `half hour offset zone hour grid should step local wall clock hours`() {
        // Australia/Lord_Howe anchors at +10:00 (1970) but sits at +10:30 in July 2026. Elapsed-hour
        // arithmetic from the anchor truncates the 0.5h offset drift (495264.5 -> 495264) and lands
        // on local :30 — off the local wall-clock hour grid. Index/key arithmetic must stay on the
        // LOCAL timeline so every bucket boundary is a real local hour.
        val zone = ZoneId.of("Australia/Lord_Howe")
        val grid = DenseDateGrid(AggregationDateUnit.HOUR, zone)
        val midnight = ZonedDateTime.of(2026, 7, 2, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val oneAM = ZonedDateTime.of(2026, 7, 2, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        val twoAM = ZonedDateTime.of(2026, 7, 2, 2, 0, 0, 0, zone).toInstant().toEpochMilli()
        grid.keysBetween(midnight, twoAM).assert().containsExactly(oneAM)
        grid.keyOf(grid.indexOf(oneAM)).assert().isEqualTo(oneAM)
    }

    @Test
    fun `week grid should anchor monday and step whole weeks`() {
        val grid = DenseDateGrid(AggregationDateUnit.WEEK, utc)
        grid.anchor.dayOfWeek.assert().isEqualTo(DayOfWeek.MONDAY)
        val week1 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli() // Monday
        val week3 = Instant.parse("2026-01-19T00:00:00Z").toEpochMilli()
        grid.keysBetween(week1, week3)
            .assert().containsExactly(Instant.parse("2026-01-12T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `quarter grid should step whole quarters`() {
        val grid = DenseDateGrid(AggregationDateUnit.QUARTER, utc)
        grid.keysBetween(
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(),
        ).assert().containsExactly(Instant.parse("2026-04-01T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `pre anchor instants floor into their containing bucket`() {
        // Temporal.until truncates toward zero for date-based units, so non-aligned instants before
        // the anchor would otherwise land one bucket ahead of the bucket that contains them.
        DenseDateGrid(AggregationDateUnit.DAY, utc)
            .indexOf(millis("1969-12-31T12:00:00Z")).assert().isEqualTo(-1L) // inside [1969-12-31, 1970-01-01)
        DenseDateGrid(AggregationDateUnit.WEEK, utc) // Monday anchor 1969-12-29
            .indexOf(millis("1969-12-28T00:00:00Z")).assert().isEqualTo(-1L) // Sunday of the previous week
        DenseDateGrid(AggregationDateUnit.MONTH, utc)
            .indexOf(millis("1969-12-15T00:00:00Z")).assert().isEqualTo(-1L)
        DenseDateGrid(AggregationDateUnit.YEAR, utc)
            .indexOf(millis("1969-06-15T00:00:00Z")).assert().isEqualTo(-1L)
    }

    @Test
    fun `zone that skipped a whole local date yields no gap keys between the two real days`() {
        // Pacific/Apia jumped from 2011-12-29 23:59:59 (-10:00) straight to 2011-12-31 00:00 (+14:00):
        // local 2011-12-30 never existed. The skipped calendar index resolves FORWARD onto the next
        // real bucket instant (keyOf collapses) and does not round-trip — it is not a grid point and
        // must produce NO bucket, even though the two real buckets sit two calendar indices apart.
        val zone = ZoneId.of("Pacific/Apia")
        val grid = DenseDateGrid(AggregationDateUnit.DAY, zone)
        val day29 = ZonedDateTime.of(2011, 12, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val day31 = ZonedDateTime.of(2011, 12, 31, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val skippedIndex = grid.indexOf(day31) - 1

        // The collapse itself: the nonexistent local midnight maps onto the next real bucket's key.
        grid.keyOf(skippedIndex).assert().isEqualTo(grid.keyOf(skippedIndex + 1))
        grid.indexOf(grid.keyOf(skippedIndex)).assert().isNotEqualTo(skippedIndex)

        // A grid point must be an EXISTING local time — the skipped index is skipped in both
        // directions, so the correct dense window between the two CONSECUTIVE real days is empty.
        grid.keysBetween(day29, day31).assert().isEmpty()
        grid.keysBetween(day31, day29).assert().isEmpty()
        grid.gapIndices(day29, day31).count().assert().isZero()
    }

    @Test
    fun `pre anchor bucket start is the greatest grid key at or before each instant`() {
        val probes = listOf(
            millis("1969-12-31T23:59:59.999Z"),
            millis("1969-12-31T00:00:00Z"),
            millis("1969-12-15T12:34:56Z"),
            millis("1969-06-15T00:00:00Z"),
        )
        AggregationDateUnit.entries.forEach { unit ->
            val grid = DenseDateGrid(unit, utc)
            probes.forEach { probe ->
                val bucketStart = grid.keyOf(grid.indexOf(probe))
                (bucketStart <= probe).assert().isTrue()
                (grid.keyOf(grid.indexOf(probe) + 1) > probe).assert().isTrue()
            }
            // Aligned keys round trip exactly, also before the anchor.
            grid.indexOf(grid.keyOf(-5L)).assert().isEqualTo(-5L)
        }
    }
}
