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
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
class TimerRunnerTest {
    private val clock = TestClock()
    private val store = InMemoryTimerStore()
    private val ledger = InMemoryFailureLedger()
    private val fired = CopyOnWriteArrayList<String>()
    private val firedAt = CopyOnWriteArrayList<Long>()

    /** What the firer answers, by timer key; a throwable is thrown. */
    private val answers = ConcurrentHashMap<String, Any>()

    /** The due time each send carried for its slot, as its target reads it. */
    private val sentDue = CopyOnWriteArrayList<Long>()
    private val firer = TimerFirer { timer ->
        fired += timer.id
        sentDue += timer.context.due
        firedAt += System.nanoTime()
        when (val answer = answers[timer.key] ?: TimerOutcomes.COMMITTED) {
            is Throwable -> throw answer
            else -> answer as String
        }
    }
    private val loops = CopyOnWriteArrayList<TimerLoop>()
    private val runners = CopyOnWriteArrayList<TimerRunner>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        runners.forEach { it.stop() }
        loops.forEach { it.close() }
    }

    private fun loop(
        expiresAfter: Duration? = null,
        maxAttempts: Int = 3,
        maxFireRate: Double? = null,
        pollInterval: Duration = 5.milliseconds,
    ): TimerLoop = TimerLoop(
        TimerOptions(
            pollInterval = pollInterval,
            lookahead = 1.seconds,
            retry = RetryPolicy(maxAttempts = maxAttempts, initialBackoff = 1.seconds, maxBackoff = 1.minutes),
            expiresAfter = expiresAfter,
            maxFireRate = maxFireRate,
            claimTimeout = CLAIM,
        ),
        clock,
    ).also { loops += it }

    private fun runner(
        expiresAfter: Duration? = null,
        timers: TimerStore = store,
        loop: TimerLoop = loop(expiresAfter),
    ): TimerRunner = TimerRunner(SHOP, timers, ledger, firer, loop).also { runners += it }

    private fun timer(key: String, dueIn: Duration = Duration.ZERO, partition: Int = 0): Timer {
        val dueAt = clock.millis() + dueIn.inWholeMilliseconds
        return Timer(
            id = "${order("o-1")}#$key@1",
            owner = order("o-1"),
            key = key,
            version = 1,
            partition = partition,
            rule = ScheduleRule.At(dueAt),
            dueAt = dueAt,
            target = order("o-1"),
            commandType = "shop.order.CancelOrder",
            body = """{"reason":"unpaid"}""",
        )
    }

    private suspend fun status(key: String): Timer = checkNotNull(store.find(order("o-1"), key))

    @Test
    fun `a timer is sent once it is due, and recorded fired with its outcome`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 500.milliseconds))
        runner().startPartition(0)
        delay(50)
        fired.assert().isEmpty()
        clock.advance(500.milliseconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        status("pay").attempts.assert().isEqualTo(1)
        delay(50)
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
        delay(50)
        fired.size.assert().isEqualTo(1)
        ledger.list(TimerOptions.PARKED_SUBSCRIPTION).assert().isEmpty()
    }

    @Test
    fun `a technical failure is retried after its backoff, then parked, resumed and skipped`(): Unit = runBlocking {
        answers["pay"] = IllegalStateException("down")
        val due = timer("pay").dueAt
        store.put(timer("pay"))
        val runner = runner()
        runner.startPartition(0)
        eventually { status("pay").attempts == 1 }
        status("pay").status.assert().isEqualTo(TimerStatus.PENDING)
        status("pay").dueAt.assert().isEqualTo(clock.millis() + 1_000)
        status("pay").outcome.assert().isEqualTo("java.lang.IllegalStateException: down")
        delay(50)
        fired.size.assert().isEqualTo(1)
        clock.advance(1.seconds)
        eventually { status("pay").attempts == 2 }
        clock.advance(2.seconds)
        eventually { status("pay").status == TimerStatus.PARKED }
        status("pay").attempts.assert().isEqualTo(3)
        eventually { ledger.parked(TimerOptions.PARKED_SUBSCRIPTION, order("o-1")) != null }
        val parked = checkNotNull(ledger.parked(TimerOptions.PARKED_SUBSCRIPTION, order("o-1")))
        parked.reason.assert().startsWith(timer("pay").id)
        clock.advance(10.minutes)
        delay(50)
        fired.size.assert().isEqualTo(3)

        answers.remove("pay")
        runner.resume(order("o-1")).assert().isEqualTo(1)
        ledger.parked(TimerOptions.PARKED_SUBSCRIPTION, order("o-1")).assert().isNull()
        eventually { status("pay").status == TimerStatus.FIRED }
        status("pay").outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        // Every attempt, retried or resumed, was sent for the slot's own due time, and the slot fired as due then.
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
        ledger.parked(TimerOptions.PARKED_SUBSCRIPTION, order("o-1")).assert().isNull()
    }

    @Test
    fun `a parking that is not written leaves the ledger alone`(): Unit = runBlocking {
        answers["pay"] = IllegalStateException("down")
        val refusing = object : TimerStore by store {
            override suspend fun settle(changes: List<TimerChange>): Int =
                if (changes.any { it.next.status == TimerStatus.PARKED }) 0 else store.settle(changes)
        }
        store.put(timer("pay"))
        runner(timers = refusing, loop = loop(maxAttempts = 1)).startPartition(0)
        eventually { fired.size == 1 }
        delay(50)
        ledger.parked(TimerOptions.PARKED_SUBSCRIPTION, order("o-1")).assert().isNull()
        // Still claimed: it is sent again once the claim lapses.
        status("pay").status.assert().isEqualTo(TimerStatus.FIRING)
    }

    @Test
    fun `a timer cancelled after it was loaded is not sent`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 500.milliseconds))
        // No poll before it is due: the wheel holds the timer as loaded.
        runner(loop = loop(pollInterval = 1.minutes)).startPartition(0)
        eventually { runners.single().loaded(0) == 1 }
        store.cancel(order("o-1"), "pay", 2).assert().isTrue()
        clock.advance(500.milliseconds)
        runners.single().partitions.assert().containsExactly(0)
        loops.single().wakeUp()
        delay(100)
        // Its claim from the row as loaded failed: it was not sent.
        fired.assert().isEmpty()
        status("pay").status.assert().isEqualTo(TimerStatus.CANCELLED)
    }

    @Test
    fun `a claim left by a node that died is sent again once it lapses`(): Unit = runBlocking {
        val timer = timer("pay")
        store.put(timer)
        // The previous owner claimed it and died before it wrote the outcome.
        store.settle(
            listOf(TimerChange(timer, timer.copy(status = TimerStatus.FIRING, dueAt = clock.millis() + 1_000)))
        )
        runner().startPartition(0)
        delay(50)
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
        delay(50)
        fired.assert().isEmpty()
        first.stopPartition(0)
        first.partitions.assert().isEmpty()
        second.startPartition(0)
        clock.advance(200.milliseconds)
        eventually { status("pay").status == TimerStatus.FIRED }
        delay(50)
        fired.assert().containsExactly(timer("pay").id)
        status("other").status.assert().isEqualTo(TimerStatus.PENDING)
    }

    @Test
    fun `an outcome that could not be written is sent again`(): Unit = runBlocking {
        val failingOnce = object : TimerStore by store {
            @Volatile
            var failed = false

            override suspend fun settle(changes: List<TimerChange>): Int {
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
        delay(50)
        // Its claim lapses, and it is sent again.
        clock.advance(CLAIM)
        eventually { status("pay").status == TimerStatus.FIRED }
        // Sent twice, with the same request id: a target deduplicates the second (ScheduledCommandsTest).
        fired.assert().containsExactly(timer("pay").id, timer("pay").id)
    }

    @Test
    fun `one loop reads each store once a round, for every partition it owns, and survives a failed read`(): Unit =
        runBlocking {
            val reads = CopyOnWriteArrayList<Set<Int>>()
            var failing = true
            val counting = object : TimerStore by store {
                override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
                    reads += partitions
                    if (failing) {
                        failing = false
                        throw IllegalStateException("The store is unreachable.")
                    }
                    return store.due(partitions, until, limit)
                }
            }
            val other = InMemoryTimerStore()
            val otherReads = CopyOnWriteArrayList<Set<Int>>()
            val counted = object : TimerStore by other {
                override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
                    otherReads += partitions
                    return other.due(partitions, until, limit)
                }
            }
            val loop = loop()
            val first = runner(timers = counting, loop = loop)
            val second = runner(timers = counted, loop = loop)
            (0 until 4).forEach { first.startPartition(it) }
            second.startPartition(2)
            store.put(timer("pay", partition = 3))
            other.put(timer("remind", partition = 2))
            eventually { status("pay").status == TimerStatus.FIRED }
            eventually { other.find(order("o-1"), "remind")?.status == TimerStatus.FIRED }
            val rounds = loop.rounds
            rounds.assert().isGreaterThan(1)
            // Never more than one read of a store per round, each of all the partitions it owns there.
            reads.size.toLong().assert().isLessThanOrEqualTo(rounds)
            reads.last().assert().containsExactlyInAnyOrder(0, 1, 2, 3)
            otherReads.size.toLong().assert().isLessThanOrEqualTo(rounds)
            otherReads.last().assert().containsExactly(2)
        }

    @Test
    fun `a backlog of overdue timers drains at the partition's rate`(): Unit = runBlocking {
        val backlog = (1..BACKLOG).map { timer("due-$it") }
        backlog.forEach { store.put(it) }
        clock.advance(10.minutes)
        runner(loop = loop(maxFireRate = RATE)).startPartition(0)
        eventually { fired.size == BACKLOG }
        val spanMillis = (firedAt.max() - firedAt.min()) / 1_000_000
        // BACKLOG firings at RATE a second take at least (BACKLOG - 1) / RATE seconds.
        spanMillis.assert().isGreaterThanOrEqualTo(((BACKLOG - 1) * 1_000 / RATE).toLong() - TOLERANCE)
        eventually { backlog.all { status(it.key).status == TimerStatus.FIRED } }
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

    private companion object {
        const val BACKLOG = 6
        const val RATE = 20.0
        const val TOLERANCE = 30L
        val CLAIM = 2.seconds
    }
}

/** A clock that stands still until a test moves it. */
class TestClock(start: Long = 1_700_000_000_000L) : Clock() {
    private val millis = AtomicLong(start)

    fun advance(by: Duration) {
        millis.addAndGet(by.inWholeMilliseconds)
    }

    override fun millis(): Long = millis.get()

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
