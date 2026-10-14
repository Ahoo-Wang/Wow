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

package me.ahoo.wow.kernel.schedule

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The cron expressions of decision D198, read as Spring's `CronExpression` reads them. */
class CronExpressionTest {
    private val utc: ZoneId = ZoneOffset.UTC

    /** The next [count] times of [expression] after [after] (UTC local time), as local times of [zone]. */
    private fun times(
        expression: String,
        after: String,
        count: Int = 3,
        zone: ZoneId = utc,
    ): List<LocalDateTime> {
        val cron = CronExpression.parse(expression)
        var at = LocalDateTime.parse(after).atZone(zone).toInstant()
        return List(count) {
            at = checkNotNull(cron.next(at, zone)) { "$expression names no time after $at" }
            LocalDateTime.ofInstant(at, zone)
        }
    }

    private fun local(vararg texts: String): List<LocalDateTime> = texts.map(LocalDateTime::parse)

    @Test
    fun `fixed values name one time a day`() {
        times("0 0 3 * * *", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-01T03:00", "2026-01-02T03:00", "2026-01-03T03:00"))
    }

    @Test
    fun `the next time is strictly after the given one`() {
        times("0 0 3 * * *", "2026-01-01T03:00", count = 1).assert().isEqualTo(local("2026-01-02T03:00"))
        val cron = CronExpression.parse("* * * * * *")
        cron.next(Instant.parse("2026-01-01T00:00:00.500Z"), utc).assert()
            .isEqualTo(Instant.parse("2026-01-01T00:00:01Z"))
    }

    @Test
    fun `steps, ranges and lists`() {
        times("*/15 * * * * *", "2026-01-01T00:00:01").assert()
            .isEqualTo(local("2026-01-01T00:00:15", "2026-01-01T00:00:30", "2026-01-01T00:00:45"))
        times("10/20 * * * * *", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-01T00:00:10", "2026-01-01T00:00:30", "2026-01-01T00:00:50"))
        times("0 0 9-17/4 * * *", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-01T09:00", "2026-01-01T13:00", "2026-01-01T17:00"))
        times("0 5,35 1 * * ?", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-01T01:05", "2026-01-01T01:35", "2026-01-02T01:05"))
    }

    @Test
    fun `names of months and days, in any case, and both Sundays`() {
        // 2026-01-01 is a Thursday.
        times("0 0 0 * * MON-FRI", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-02T00:00", "2026-01-05T00:00", "2026-01-06T00:00"))
        times("0 0 0 1 jan,Jul *", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-07-01T00:00", "2027-01-01T00:00", "2027-07-01T00:00"))
        times("0 0 0 * * 0", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-04T00:00"))
        times("0 0 0 * * 7", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-04T00:00"))
        times("0 0 0 * * sun", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-04T00:00"))
    }

    @Test
    fun `a monthly rule runs on the first of every month, leap years included`() {
        times("0 0 0 1 * *", "2026-01-15T00:00", count = 4).assert()
            .isEqualTo(local("2026-02-01T00:00", "2026-03-01T00:00", "2026-04-01T00:00", "2026-05-01T00:00"))
        times("0 0 0 29 2 *", "2026-01-01T00:00", count = 2).assert()
            .isEqualTo(local("2028-02-29T00:00", "2032-02-29T00:00"))
    }

    @Test
    fun `the last day of the month and the days before it`() {
        times("0 0 0 L * *", "2026-01-01T00:00").assert()
            .isEqualTo(local("2026-01-31T00:00", "2026-02-28T00:00", "2026-03-31T00:00"))
        times("0 0 0 L-2 * *", "2026-01-01T00:00", count = 2).assert()
            .isEqualTo(local("2026-01-29T00:00", "2026-02-26T00:00"))
    }

    @Test
    fun `the last weekday and the weekday nearest to a day`() {
        // 2026-01-31 is a Saturday, 2026-05-31 a Sunday.
        times("0 0 0 LW * *", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-30T00:00"))
        times("0 0 0 LW * *", "2026-05-01T00:00", count = 1).assert().isEqualTo(local("2026-05-29T00:00"))
        times("0 0 0 LW * *", "2026-03-01T00:00", count = 1).assert().isEqualTo(local("2026-03-31T00:00"))
        // 2026-03-15 is a Sunday: Monday the 16th; 2026-02-14 is a Saturday: Friday the 13th.
        times("0 0 0 15W * *", "2026-03-01T00:00", count = 1).assert().isEqualTo(local("2026-03-16T00:00"))
        times("0 0 0 14W * *", "2026-02-01T00:00", count = 1).assert().isEqualTo(local("2026-02-13T00:00"))
        // 2026-08-01 is a Saturday: not back into July, Monday the 3rd; 2026-05-31 a Sunday: Friday the 29th.
        times("0 0 0 1W * *", "2026-07-15T00:00", count = 1).assert().isEqualTo(local("2026-08-03T00:00"))
        times("0 0 0 31W * *", "2026-05-01T00:00", count = 1).assert().isEqualTo(local("2026-05-29T00:00"))
        // A day beyond a month's length has no weekday there: February is skipped.
        times("0 0 0 30W * *", "2026-02-01T00:00", count = 1).assert().isEqualTo(local("2026-03-30T00:00"))
        // 2026-01-14 is a Wednesday: the day itself.
        times("0 0 0 14W * *", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-14T00:00"))
    }

    @Test
    fun `the last and the nth day of the week in a month`() {
        times("0 0 0 * * 5L", "2026-01-01T00:00", count = 2).assert()
            .isEqualTo(local("2026-01-30T00:00", "2026-02-27T00:00"))
        times("0 0 0 * * FRIL", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-30T00:00"))
        times("0 0 0 * * MON#1", "2026-01-01T00:00", count = 2).assert()
            .isEqualTo(local("2026-01-05T00:00", "2026-02-02T00:00"))
        times("0 0 0 * * 0#2", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-11T00:00"))
    }

    @Test
    fun `a day matches both day fields`() {
        // Friday the 13th.
        times("0 0 0 13 * FRI", "2026-01-01T00:00", count = 2).assert()
            .isEqualTo(local("2026-02-13T00:00", "2026-03-13T00:00"))
    }

    @Test
    fun `the macros`() {
        times("@yearly", "2026-03-01T00:00", count = 1).assert().isEqualTo(local("2027-01-01T00:00"))
        times("@annually", "2026-03-01T00:00", count = 1).assert().isEqualTo(local("2027-01-01T00:00"))
        times("@monthly", "2026-03-02T00:00", count = 1).assert().isEqualTo(local("2026-04-01T00:00"))
        times("@weekly", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-04T00:00"))
        times("@daily", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-02T00:00"))
        times("@MIDNIGHT", "2026-01-01T00:00", count = 1).assert().isEqualTo(local("2026-01-02T00:00"))
        times("@hourly", "2026-01-01T00:30", count = 1).assert().isEqualTo(local("2026-01-01T01:00"))
    }

    @Test
    fun `the fields are local times of the time zone`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val cron = CronExpression.parse("0 0 9 * * *")
        cron.next(Instant.parse("2026-01-01T00:00:00Z"), shanghai).assert()
            .isEqualTo(Instant.parse("2026-01-01T01:00:00Z"))
        cron.next(Instant.parse("2026-01-01T00:00:00Z"), utc).assert()
            .isEqualTo(Instant.parse("2026-01-01T09:00:00Z"))
    }

    @Test
    fun `a local time a daylight saving gap skips runs later by the gap`() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-03-08 02:00 EST jumps to 03:00 EDT.
        times("0 30 2 * * *", "2026-03-07T12:00", zone = newYork).assert()
            .isEqualTo(local("2026-03-08T03:30", "2026-03-09T02:30", "2026-03-10T02:30"))
        // Every half hour: 02:00 runs at 03:00 and 02:30 at 03:30, so 03:00 and 03:30 do not run twice.
        times("0 0/30 * * * *", "2026-03-08T01:00", count = 5, zone = newYork).assert().isEqualTo(
            local(
                "2026-03-08T01:30",
                "2026-03-08T03:00",
                "2026-03-08T03:30",
                "2026-03-08T04:00",
                "2026-03-08T04:30",
            ),
        )
    }

    @Test
    fun `a local time an overlap repeats runs once, at its earlier offset`() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-11-01 02:00 EDT falls back to 01:00 EST.
        val cron = CronExpression.parse("0 30 1 * * *")
        val first = checkNotNull(cron.next(Instant.parse("2026-11-01T04:00:00Z"), newYork))
        first.assert().isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
        cron.next(first, newYork).assert().isEqualTo(Instant.parse("2026-11-02T06:30:00Z"))
        // After the earlier 01:30 ran, a time inside the repeated hour does not run it again.
        cron.next(Instant.parse("2026-11-01T06:10:00Z"), newYork).assert()
            .isEqualTo(Instant.parse("2026-11-02T06:30:00Z"))
    }

    @Test
    fun `an expression that names no time has no next`() {
        CronExpression.parse("0 0 0 30 2 *").next(Instant.parse("2026-01-01T00:00:00Z"), utc).assert().isNull()
    }

    @Test
    fun `an invalid expression says what is wrong`() {
        assertThrownBy<IllegalArgumentException> { CronExpression.parse(" ") }
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("0 0 * * *") }.hasMessageContaining("5 fields")
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("60 * * * * *") }.hasMessageContaining("\"60\"")
        assertThrownBy<IllegalArgumentException> {
            CronExpression.parse(
                "0 0 5-1 * * *"
            )
        }.hasMessageContaining("backwards")
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("*/0 * * * * *") }.hasMessageContaining("step")
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("0 0 0 * FOO *") }
        assertThrownBy<IllegalArgumentException> {
            CronExpression.parse(
                "0 0 0 1,,2 * *"
            )
        }.hasMessageContaining("empty")
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("0 0 0 L-0 * *") }
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("0 0 0 * * MON#6") }
        assertThrownBy<IllegalArgumentException> { CronExpression.parse("0 0 0 * * L") }
        CronExpression.isValid("0 0 0 * * *").assert().isTrue()
        CronExpression.isValid("nonsense").assert().isFalse()
    }

    @Test
    fun `an expression is its text`() {
        val cron = CronExpression.parse("0 0 3 * * *")
        cron.toString().assert().isEqualTo("0 0 3 * * *")
        cron.expression.assert().isEqualTo("0 0 3 * * *")
        cron.assert().isEqualTo(CronExpression.parse("0 0 3 * * *"))
        cron.hashCode().assert().isEqualTo(CronExpression.parse("0 0 3 * * *").hashCode())
        cron.assert().isNotEqualTo(CronExpression.parse("0 0 4 * * *"))
        (cron == Any()).assert().isFalse()
    }
}
