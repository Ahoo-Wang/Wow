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
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The timer runner of one context (decision D166) over an in-memory store, with a firer that records what it sends
 * and a clock the test moves: when a timer is sent, what each outcome records, the retries and the parking, an
 * expiry, a cancel that wins the race, and a handover.
 */
class TimerRunnerTest {
    private val clock = TestClock()
    private val store = InMemoryTimerStore()
    private val ledger = InMemoryFailureLedger()
    private val fired = CopyOnWriteArrayList<String>()

    /** What the firer answers, by timer key; a throwable is thrown. */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val firer = TimerFirer { timer ->
        fired += timer.id
        when (val answer = answers[timer.key] ?: TimerOutcomes.COMMITTED) {
            is Throwable -> throw answer
            else -> answer as String
        }
    }
    private val runners = CopyOnWriteArrayList<TimerRunner>()

    @AfterEach
    fun stop(): Unit = runBlocking { runners.forEach { it.stop() } }

    private fun runner(
        expiresAfter: Duration? = null,
        timers: TimerStore = store,
        maxAttempts: Int = 3,
    ): TimerRunner = TimerRunner(
        SHOP,
        timers,
        ledger,
        firer,
        TimerOptions(
            pollInterval = 5.milliseconds,
            lookahead = 1.seconds,
            retry = RetryPolicy(maxAttempts = maxAttempts, initialBackoff = 1.seconds, maxBackoff = 1.minutes),
            expiresAfter = expiresAfter,
        ),
        clock,
    ).also { runners += it }

    private fun timer(key: String, dueIn: Duration = Duration.ZERO, partition: Int = 0) = Timer(
        id = "${order("o-1")}#$key@1",
        owner = order("o-1").toString(),
        key = key,
        version = 1,
        partition = partition,
        dueAt = clock.millis() + dueIn.inWholeMilliseconds,
        target = order("o-1"),
        commandType = "shop.order.CancelOrder",
        body = """{"reason":"unpaid"}""",
    )

    private suspend fun status(key: String): Timer = checkNotNull(store.find(order("o-1").toString(), key))

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
    fun `a timer cancelled after it was loaded is not sent`(): Unit = runBlocking {
        store.put(timer("pay", dueIn = 500.milliseconds))
        runner().startPartition(0)
        delay(50)
        // Loaded into the wheel, then cancelled before it is due: the runner reads it again before sending it.
        store.cancel(order("o-1").toString(), "pay", 2).assert().isTrue()
        clock.advance(500.milliseconds)
        delay(100)
        fired.assert().isEmpty()
        status("pay").status.assert().isEqualTo(TimerStatus.CANCELLED)
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
    fun `an outcome that could not be recorded is sent again`(): Unit = runBlocking {
        val failingOnce = object : TimerStore by store {
            @Volatile
            var failed = false

            override suspend fun update(timer: Timer, expected: TimerStatus): Boolean {
                if (!failed && timer.status == TimerStatus.FIRED) {
                    failed = true
                    throw IllegalStateException("The store is unreachable.")
                }
                return store.update(timer, expected)
            }
        }
        store.put(timer("pay"))
        runner(timers = failingOnce).startPartition(0)
        eventually { status("pay").status == TimerStatus.FIRED }
        // Sent twice, with the same request id: a target deduplicates the second (ScheduledCommandsTest).
        fired.assert().containsExactly(timer("pay").id, timer("pay").id)
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
