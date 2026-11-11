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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The timers of one context's log group (decisions D166, D198) fired by a node's loop over an in-memory store, with a
 * firer that records what it sends and a clock the test moves: when a timer is sent, what each outcome records, the
 * retries and the parking, an expiry, a cancel that wins the race, a handover, one query a round over every partition,
 * and the rate a backlog drains at.
 */
class TimerRunnerTest : TimerRunnerFixtures() {
    @Test
    fun `a timer is sent once it is due, and recorded fired with its outcome`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 500.milliseconds))
        runner().startPartition(0)
        // A round read it, not due, and left it in the wheel: a timer leaves the wheel only to be sent.
        rounds(2)
        runners.single().loaded(0).assert().isEqualTo(1)
        fired.assert().isEmpty()
        clock.advance(500.milliseconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        status("pay").attempts.assert().isEqualTo(1)
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3)
        fired.assert().containsExactly(timer("pay").id)
    }

    @Test
    fun `a rejection is a fired timer, not retried`(): Unit = runBlocking {
        answers["pay"] = TimerOutcomes.REJECTED
        store.put(timer("pay"))
        runner().startPartition(0)
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.REJECTED)
        clock.advance(10.minutes)
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3)
        fired.size.assert().isEqualTo(1)
        ledger.list(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION)).assert().isEmpty()
    }

    @Test
    fun `a technical failure is retried after its backoff, then parked, retried and skipped`(): Unit = runBlocking {
        answers["pay"] = IllegalStateException("down")
        val due = timer("pay").dueAt
        store.put(timer("pay"))
        val runner = runner()
        runner.startPartition(0)
        eventually { status("pay").attempts == 1 }
        status("pay").status.assert().isEqualTo(TimerStatus.PENDING)
        status("pay").dueAt.assert().isEqualTo(clock.millis() + 1_000)
        status("pay").outcome.assert().isEqualTo("java.lang.IllegalStateException: down")
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3)
        fired.size.assert().isEqualTo(1)
        clock.advance(1.seconds)
        eventually { status("pay").attempts == 2 }
        clock.advance(2.seconds)
        eventually { status("pay").status == TimerStatus.PARKED }
        status("pay").attempts.assert().isEqualTo(3)
        eventually { ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")) != null }
        val parked = checkNotNull(ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")))
        parked.reason.assert().startsWith(timer("pay").id)
        clock.advance(10.minutes)
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3)
        fired.size.assert().isEqualTo(3)

        answers.remove("pay")
        runner.retry(order("o-1")).assert().isEqualTo(1)
        ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNull()
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        // Every attempt, retried or retried, was sent for the slot's own due time, and the slot fired as due then.
        sentDue.assert().hasSize(4).allMatch { it == due }
        status("pay").dueAt.assert().isEqualTo(due)
        status("pay").slotDueAt.assert().isEqualTo(due)

        answers["remind"] = IllegalStateException("down")
        store.put(timer("remind"))
        eventually { status("remind").attempts == 1 }
        clock.advance(1.seconds)
        eventually { status("remind").attempts == 2 }
        clock.advance(2.seconds)
        eventually { status("remind").status == TimerStatus.PARKED }
        runner.skip(order("o-1")).assert().isEqualTo(1)
        status("remind").status.assert().isEqualTo(TimerStatus.CANCELLED)
        status("remind").outcome.assert().isEqualTo(TimerOutcomes.SKIPPED)
        ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNull()
    }

    @Test
    fun `a parking the store does not write leaves its ledger entry, which a retry resolves finding nothing parked`():
        Unit = runBlocking {
        answers["pay"] = IllegalStateException("down")
        val refusing = object : TimerStore by store {
            override suspend fun settle(changes: List<TimerChange>): List<Boolean> =
                if (changes.any { it.next.status == TimerStatus.PARKED }) {
                    changes.map { false }
                } else {
                    store.settle(changes)
                }
        }
        store.put(timer("pay"))
        val runner = runner(timers = refusing, loop = loop(maxAttempts = 1))
        runner.startPartition(0)
        eventually { ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")) != null }
        // Still claimed: it is sent again once the claim lapses.
        status("pay").status.assert().isEqualTo(TimerStatus.FIRING)
        runner.retry(order("o-1")).assert().isEqualTo(0)
        ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNull()
    }

    @Test
    fun `a timer is never parked without its ledger entry, and is parked once the ledger records it`(): Unit =
        runBlocking {
            answers["pay"] = IllegalStateException("down")
            val parks = AtomicInteger()
            val outage = object : FailureLedger by ledger {
                override suspend fun park(entry: ParkedAggregate): Boolean {
                    if (parks.incrementAndGet() == 1) throw IOException("The ledger is down for one write.")
                    return ledger.park(entry)
                }
            }
            store.put(timer("pay"))
            TimerRunner(SHOP, store, outage, firer, loop(maxAttempts = 1)).also { runners += it }.startPartition(0)
            // Its parking failed: PENDING again, its last attempt sent again after the backoff.
            eventually { parks.get() == 1 && status("pay").status == TimerStatus.PENDING }
            status("pay").attempts.assert().isEqualTo(0)
            status("pay").dueAt.assert().isEqualTo(clock.millis() + 1_000)
            store.parked(order("o-1")).assert().isEmpty()
            clock.advance(1.seconds)
            eventually { status("pay").status == TimerStatus.PARKED }
            fired.size.assert().isEqualTo(2)
            ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNotNull()
        }

    @Test
    fun `a parking cut short after its ledger entry is parked again by the next owner of the partition`(): Unit =
        runBlocking {
            answers["pay"] = IllegalStateException("down")
            // The node dies between the two writes: the ledger records the target, the timer stays claimed.
            val dying = object : TimerStore by store {
                override suspend fun settle(changes: List<TimerChange>): List<Boolean> =
                    if (changes.any { it.next.status == TimerStatus.PARKED }) {
                        error("The node died.")
                    } else {
                        store.settle(changes)
                    }
            }
            store.put(timer("pay"))
            val first = runner(timers = dying, loop = loop(maxAttempts = 1))
            first.startPartition(0)
            eventually { ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")) != null }
            first.stop()
            status("pay").status.assert().isEqualTo(TimerStatus.FIRING)

            // The next owner sends it again once the claim lapses, and parks it with its entry.
            runner(loop = loop(maxAttempts = 1)).startPartition(0)
            clock.advance(CLAIM)
            eventually { status("pay").status == TimerStatus.PARKED }
            fired.size.assert().isEqualTo(2)
            ledger.parked(SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION), order("o-1")).assert().isNotNull()
        }

    @Test
    fun `a timer cancelled after it was loaded is not sent`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 500.milliseconds))
        val claims = AtomicInteger()
        val claiming = object : TimerStore by store {
            override suspend fun settle(changes: List<TimerChange>): List<Boolean> {
                if (changes.any { it.next.status == TimerStatus.FIRING }) {
                    claims.incrementAndGet()
                }
                return store.settle(changes)
            }
        }
        // No poll before it is due: the wheel holds the timer as loaded.
        runner(timers = claiming, loop = loop(pollInterval = 1.minutes)).startPartition(0)
        eventually { runners.single().loaded(0) == 1 }
        store.cancel(order("o-1"), "pay", 2).assert().isTrue()
        clock.advance(500.milliseconds)
        runners.single().partitions.assert().containsExactly(0)
        loops.single().wakeUp()
        // Its claim from the row as loaded failed, and a send whose claim fails ends there: it was not sent.
        eventually { claims.get() == 1 }
        fired.assert().isEmpty()
        status("pay").status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `a claim left by a node that died is sent again once it lapses`(): Unit = runBlocking {
        val timer = timer("pay")
        store.put(timer)
        // The previous owner claimed it and died before it wrote the outcome.
        store.settle(
            listOf(TimerChange(timer, timer.copyWith(status = TimerStatus.FIRING, dueAt = clock.millis() + 1_000)))
        )
        runner().startPartition(0)
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3)
        fired.assert().isEmpty()
        clock.advance(1.seconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        fired.assert().containsExactly(timer.id)
        // Claimed anew, it is still sent for the slot's own due time.
        sentDue.assert().containsExactly(timer.dueAt)
        status("pay").dueAt.assert().isEqualTo(timer.dueAt)
    }

    @Test
    fun `a timer overdue after an outage is sent at once, unless it expired`(): Unit = runBlocking {
        store.put(timer("pay"))
        store.put(timer("remind", dueIn = 30.seconds))
        // No owner while the timers fall due.
        clock.advance(1.minutes)
        runner(expiresAfter = 45.seconds).startPartition(0)
        eventually { status("pay").status != TimerStatus.PENDING && status("remind").status != TimerStatus.PENDING }
        status("pay").status.assert().isEqualTo(TimerStatus.CANCELLED)
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.EXPIRED)
        status("remind").status.assert().isEqualTo(TimerStatus.FIRED)
        fired.assert().containsExactly(timer("remind").id)
    }

    @Test
    fun `a partition handed over is sent by its new owner, and only the partitions owned are sent`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 200.milliseconds))
        store.put(timer("other", partition = 1))
        val first = runner()
        val second = runner()
        first.startPartition(0)
        first.partitions.assert().containsExactly(0)
        // A round read it, not due, and left it in the wheel: a timer leaves the wheel only to be sent.
        rounds(2, loops.first())
        first.loaded(0).assert().isEqualTo(1)
        fired.assert().isEmpty()
        first.stopPartition(0)
        first.partitions.assert().isEmpty()
        second.startPartition(0)
        clock.advance(200.milliseconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        // Three rounds, not two: a send dispatched in a round runs in its own coroutine, which records it
        // maybe after the next round starts.
        rounds(3, loops.last())
        fired.assert().containsExactly(timer("pay").id)
        status("other").status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `an outcome that could not be written is sent again`(): Unit = runBlocking {
        val failingOnce = object : TimerStore by store {
            @Volatile
            var failed = false

            override suspend fun settle(changes: List<TimerChange>): List<Boolean> {
                if (!failed && changes.any { it.next.status == TimerStatus.FIRED }) {
                    failed = true
                    throw IllegalStateException("The store is unreachable.")
                }
                return store.settle(changes)
            }
        }
        store.put(timer("pay"))
        runner(timers = failingOnce).startPartition(0)
        eventually { fired.size == 1 }
        // The write of its outcome failed: the timer stays claimed.
        eventually { failingOnce.failed }
        // Its claim lapses, and it is sent again.
        clock.advance(CLAIM)
        eventually { status("pay").status == TimerStatus.FIRED }
        // Sent twice, with the same request id: a target deduplicates the second (ScheduledCommandsTest).
        fired.assert().containsExactly(timer("pay").id, timer("pay").id)
    }

    @Test
    fun `a rate is positive`() {
        assertThrows<IllegalArgumentException> { TimerOptions(maxFireRate = 0.0) }
    }

    @Test
    fun `the maximum early wait is never negative, and none is allowed`() {
        assertThrows<IllegalArgumentException> { TimerOptions(maxEarlyWait = (-1).seconds) }
        TimerOptions(maxEarlyWait = Duration.ZERO).maxEarlyWait.assert().isEqualTo(Duration.ZERO)
    }
}
