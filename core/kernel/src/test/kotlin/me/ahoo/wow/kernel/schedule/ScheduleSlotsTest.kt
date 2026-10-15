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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.Test
import java.time.Instant

/** How a schedule steps through its slots (decision D198): first due, next slot, coalesced or caught up. */
class ScheduleSlotsTest {
    private val ref = AggregateType("billing", "account").ref("t", "a-1")
    private val monthly = ScheduleRule.Cron("0 0 0 1 * *")

    private fun at(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun timer(rule: ScheduleRule, due: Long, slot: Long = 1, skipped: Long = 0) = Timer(
        id = "$ref#k@1",
        owner = ref,
        key = "k",
        version = 1,
        partition = 0,
        rule = rule,
        dueAt = due,
        target = ref,
        commandType = "billing.account.Invoice",
        body = "{}",
        slot = slot,
        status = TimerStatus.FIRING,
        attempts = 2,
        slotDueAt = due,
        skipped = skipped,
    )

    @Test
    fun `a rule due once is first due as it says, a cron rule at its first time after the commit`() {
        val commit = at("2026-01-15T10:00:00Z")
        ScheduleSlots.firstDue(ScheduleRule.At(5), commit).assert().isEqualTo(5L)
        ScheduleSlots.firstDue(ScheduleRule.After(1_000), commit).assert().isEqualTo(commit + 1_000)
        ScheduleSlots.firstDue(monthly, commit).assert().isEqualTo(at("2026-02-01T00:00:00Z"))
        ScheduleSlots.firstDue(ScheduleRule.Cron("0 0 0 30 2 *"), commit).assert().isEqualTo(Timers.NOT_EVALUATED)
        ScheduleSlots.next(monthly, at("2026-02-01T00:00:00Z")).assert().isEqualTo(at("2026-03-01T00:00:00Z"))
    }

    @Test
    fun `the timer of a cron rule is first due at its first time, others as their rule says`() {
        val commit = at("2026-01-15T10:00:00Z")
        val stream = EventStreamRecord(
            ref = ref,
            version = 3,
            commandId = "c-1",
            requestId = "r-1",
            commandType = "billing.account.Open",
            commitTime = commit,
            events = listOf(EventRecord("billing.account.Opened", 1, "{}")),
            scope = Scope.NONE,
        )
        val cron = ScheduleSlots.timerOf(stream, ScheduleSet("k", monthly, ref, "billing.account.Invoice", "{}"), 4)
        cron.dueAt.assert().isEqualTo(at("2026-02-01T00:00:00Z"))
        cron.slotDueAt.assert().isEqualTo(cron.dueAt)
        cron.slot.assert().isEqualTo(1L)
        val once = ScheduleSlots.timerOf(
            stream,
            ScheduleSet("k", ScheduleRule.After(10), ref, "billing.account.Invoice", "{}"),
            4,
        )
        once.dueAt.assert().isEqualTo(commit + 10)
    }

    @Test
    fun `a slot past due by several times of a coalescing rule moves to the latest one, the others skipped`() {
        val due = timer(monthly, at("2026-02-01T00:00:00Z"), slot = 1)
        val caught = ScheduleSlots.caughtUp(due, at("2026-05-02T00:00:00Z"))
        caught.slot.assert().isEqualTo(4L)
        caught.slotDueAt.assert().isEqualTo(at("2026-05-01T00:00:00Z"))
        caught.skipped.assert().isEqualTo(3L)
        caught.context.skipped.assert().isEqualTo(3L)
        // Its due time stays; the claim gives it the claim's deadline.
        caught.dueAt.assert().isEqualTo(due.dueAt)
    }

    @Test
    fun `a slot due by its own time only, a catching-up rule or a rule due once is not caught up`() {
        val due = timer(monthly, at("2026-02-01T00:00:00Z"))
        ScheduleSlots.caughtUp(due, at("2026-02-15T00:00:00Z")).assert().isSameAs(due)
        val catchUp = timer(ScheduleRule.Cron("0 0 0 1 * *", misfire = Misfire.CATCH_UP), at("2026-02-01T00:00:00Z"))
        ScheduleSlots.caughtUp(catchUp, at("2026-06-01T00:00:00Z")).assert().isSameAs(catchUp)
        val once = timer(ScheduleRule.At(1), 1)
        ScheduleSlots.caughtUp(once, at("2026-06-01T00:00:00Z")).assert().isSameAs(once)
    }

    @Test
    fun `a fired slot moves on to the next time after its own, pending with its attempts reset`() {
        val fired = timer(monthly, at("2026-02-01T00:00:00Z"), slot = 1)
        val next = checkNotNull(ScheduleSlots.afterFired(fired, at("2026-02-01T00:00:03Z")))
        next.status.assert().isEqualTo(TimerStatus.PENDING)
        next.slot.assert().isEqualTo(2L)
        next.slotDueAt.assert().isEqualTo(at("2026-03-01T00:00:00Z"))
        next.dueAt.assert().isEqualTo(next.slotDueAt)
        next.skipped.assert().isEqualTo(0L)
        next.attempts.assert().isEqualTo(0)
    }

    @Test
    fun `a slot fired late coalesces the times it missed, or catches them up one by one`() {
        val late = at("2026-04-10T00:00:00Z")
        val coalesced = checkNotNull(ScheduleSlots.afterFired(timer(monthly, at("2026-02-01T00:00:00Z")), late))
        // March was missed: April runs as the third slot.
        coalesced.slot.assert().isEqualTo(3L)
        coalesced.slotDueAt.assert().isEqualTo(at("2026-04-01T00:00:00Z"))
        coalesced.skipped.assert().isEqualTo(1L)
        val catchUpRule = ScheduleRule.Cron("0 0 0 1 * *", misfire = Misfire.CATCH_UP)
        val caughtUp = checkNotNull(ScheduleSlots.afterFired(timer(catchUpRule, at("2026-02-01T00:00:00Z")), late))
        caughtUp.slot.assert().isEqualTo(2L)
        caughtUp.slotDueAt.assert().isEqualTo(at("2026-03-01T00:00:00Z"))
        caughtUp.skipped.assert().isEqualTo(0L)
    }

    @Test
    fun `a rule due once, or one without a next time, is done once fired`() {
        ScheduleSlots.afterFired(timer(ScheduleRule.At(1), 1), 2).assert().isNull()
        // February has no 30th: the expression names no time.
        val leap = timer(ScheduleRule.Cron("0 0 0 30 2 *"), at("2026-01-01T00:00:00Z"))
        ScheduleSlots.afterFired(leap, at("2026-01-01T00:00:00Z")).assert().isNull()
    }

    @Test
    fun `beyond the counted slots, the rest are estimated from their period`() {
        val everySecond = ScheduleRule.Cron("* * * * * *")
        val from = at("2026-01-01T00:00:00Z")
        val now = from + 2 * 86_400_000L + 500
        val caught = ScheduleSlots.caughtUp(timer(everySecond, from), now)
        caught.slotDueAt.assert().isEqualTo(now - 500)
        caught.slot.assert().isEqualTo(1 + 2 * 86_400L)
        caught.skipped.assert().isEqualTo(2 * 86_400L)
    }

    @Test
    fun `an invalid expression or time zone is refused`() {
        assertThrownBy<IllegalArgumentException> { ScheduleSlots.validate(ScheduleRule.Cron("nonsense")) }
        assertThrownBy<IllegalArgumentException> {
            ScheduleSlots.validate(ScheduleRule.Cron("0 0 0 * * *", "Mars/Olympus"))
        }.hasMessageContaining("Mars/Olympus")
        ScheduleSlots.validate(ScheduleRule.Cron("0 0 0 * * *", "Asia/Shanghai"))
        ScheduleSlots.expressionOf(monthly).assert().isSameAs(ScheduleSlots.expressionOf(monthly))
        ScheduleSlots.zoneOf(monthly).id.assert().isEqualTo("UTC")
    }
}
