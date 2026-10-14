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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Recurring timers on a node's loop (decision D198), with a clock the test moves: a monthly rule fires slot after slot,
 * coalesces the months it missed or catches them up, an expired slot moves on, and a timer written on this node, or a
 * recurring timer's next slot, enters the firing wheel without waiting for the next read of the store.
 */
class RecurringTimerRunnerTest {
    private val clock = TestClock(Instant.parse("2026-01-15T00:00:00Z").toEpochMilli())
    private val store = InMemoryTimerStore()
    private val sent = CopyOnWriteArrayList<ScheduleContext>()
    private val firer = TimerFirer { timer ->
        sent += timer.context
        TimerOutcomes.COMMITTED
    }
    private val loops = CopyOnWriteArrayList<TimerLoop>()
    private val runners = CopyOnWriteArrayList<TimerRunner>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        runners.forEach { it.stop() }
        loops.forEach { it.close() }
    }

    private fun runner(
        expiresAfter: Duration? = null,
        pollInterval: Duration = 5.milliseconds,
        lookahead: Duration = 1.seconds,
    ): TimerRunner {
        val loop = TimerLoop(
            TimerOptions(
                pollInterval = pollInterval,
                lookahead = lookahead,
                retry = RetryPolicy(maxAttempts = 3, initialBackoff = 1.seconds, maxBackoff = 1.minutes),
                expiresAfter = expiresAfter,
                claimTimeout = 2.seconds,
            ),
            clock,
        ).also { loops += it }
        return TimerRunner(SHOP, store, InMemoryFailureLedger(), firer, loop).also { runners += it }
    }

    private fun at(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun timer(rule: ScheduleRule.Cron, key: String = "invoice", partition: Int = 0): Timer {
        val due = ScheduleSlots.firstDue(rule, clock.millis())
        return Timer(
            id = "${order("o-1")}#$key@1",
            owner = order("o-1").toString(),
            key = key,
            version = 1,
            partition = partition,
            rule = rule,
            dueAt = due,
            target = order("o-1"),
            commandType = "shop.order.CancelOrder",
            body = """{"reason":"monthly"}""",
        )
    }

    private suspend fun held(key: String = "invoice"): Timer = checkNotNull(store.find(order("o-1").toString(), key))

    private fun moveTo(text: String) {
        clock.advance((at(text) - clock.millis()).milliseconds)
    }

    @Test
    fun `a monthly timer fires slot after slot, each at its own due time`(): Unit = runBlocking {
        store.put(timer(MONTHLY))
        runner().startPartition(0)
        moveTo("2026-02-01T00:00:00Z")
        eventually { held().slot == 2L && held().status == TimerStatus.PENDING }
        held().slotDueAt.assert().isEqualTo(at("2026-03-01T00:00:00Z"))
        held().outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        held().attempts.assert().isEqualTo(0)
        moveTo("2026-03-01T00:00:00Z")
        eventually { held().slot == 3L }
        sent.map { it.slot to it.due }.assert().containsExactly(
            1L to at("2026-02-01T00:00:00Z"),
            2L to at("2026-03-01T00:00:00Z"),
        )
        sent.map { it.skipped }.assert().containsExactly(0L, 0L)
    }

    @Test
    fun `months missed while no node fired are coalesced into the latest`(): Unit = runBlocking {
        store.put(timer(MONTHLY))
        // February to May pass with no node running: June 2nd, the slot of May runs once.
        moveTo("2026-05-02T00:00:00Z")
        runner().startPartition(0)
        eventually { held().slot == 5L }
        sent.single().assert().isEqualTo(ScheduleContext(held().id, "invoice", 4, at("2026-05-01T00:00:00Z"), 3))
        held().slotDueAt.assert().isEqualTo(at("2026-06-01T00:00:00Z"))
        held().skipped.assert().isEqualTo(0L)
    }

    @Test
    fun `a catching-up timer sends each slot it missed in turn`(): Unit = runBlocking {
        store.put(timer(MONTHLY.copy(misfire = Misfire.CATCH_UP)))
        moveTo("2026-04-02T00:00:00Z")
        runner().startPartition(0)
        eventually { held().slot == 4L }
        sent.map { it.slot }.assert().containsExactly(1L, 2L, 3L)
        sent.map { it.skipped }.distinct().assert().containsExactly(0L)
    }

    @Test
    fun `an expired slot of a recurring timer moves on to the next one, unsent`(): Unit = runBlocking {
        store.put(timer(DAILY))
        moveTo("2026-01-16T03:00:00Z")
        runner(expiresAfter = 1.hours).startPartition(0)
        eventually { held().slot == 2L }
        held().status.assert().isEqualTo(TimerStatus.PENDING)
        held().outcome.assert().isEqualTo(TimerOutcomes.EXPIRED)
        held().slotDueAt.assert().isEqualTo(at("2026-01-17T00:00:00Z"))
        sent.assert().isEmpty()
    }

    @Test
    fun `a timer written on this node and due soon is fired without another read of the store`(): Unit = runBlocking {
        val runner = runner(pollInterval = 1.minutes)
        runner.startPartition(0)
        eventually { runner.loopRounds() >= 1 }
        val rounds = runner.loopRounds()
        val timer = timer(EVERY_SECOND)
        store.put(timer)
        runner.offer(timer)
        clock.advance(1.seconds)
        eventually { sent.size >= 1 }
        // Its next slot went back into the wheel when its outcome was written.
        clock.advance(1.seconds)
        eventually { sent.size >= 2 }
        sent.map { it.slot }.take(2).assert().containsExactly(1L, 2L)
        runner.loopRounds().assert().isEqualTo(rounds)
    }

    @Test
    fun `a timer of another partition, not pending, or due beyond the lookahead is not taken`(): Unit = runBlocking {
        val runner = runner(pollInterval = 1.minutes)
        runner.startPartition(0)
        eventually { runner.loopRounds() >= 1 }
        runner.offer(timer(EVERY_SECOND, key = "other", partition = 1))
        runner.offer(timer(EVERY_SECOND, key = "done").copy(status = TimerStatus.FIRED))
        runner.offer(timer(MONTHLY, key = "later"))
        runner.loaded(0).assert().isEqualTo(0)
        runner.loaded(1).assert().isEqualTo(0)
        val due = timer(EVERY_SECOND, key = "due")
        runner.offer(due)
        runner.offer(due)
        runner.loaded(0).assert().isEqualTo(1)
        delay(20)
        sent.assert().isEmpty()
    }

    private fun TimerRunner.loopRounds(): Long = runners.indexOf(this).let { loops[it].rounds }

    private companion object {
        val MONTHLY = ScheduleRule.Cron("0 0 0 1 * *")
        val DAILY = ScheduleRule.Cron("0 0 0 * * *")
        val EVERY_SECOND = ScheduleRule.Cron("* * * * * *")
    }
}
