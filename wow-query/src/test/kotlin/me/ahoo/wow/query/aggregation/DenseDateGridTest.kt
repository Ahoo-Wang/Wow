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
}
