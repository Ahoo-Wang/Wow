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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Timers over an in-memory store and ledger, on a clock that stands still until a test moves it, sent by a firer
 * whose answers, gates and send times each test sets: what the timer runner's tests share, each test with its own.
 */
abstract class TimerRunnerFixtures {
    protected val clock = TestClock()
    protected val store = InMemoryTimerStore()
    protected val ledger = InMemoryFailureLedger()
    protected val fired = CopyOnWriteArrayList<String>()
    protected val firedAt = CopyOnWriteArrayList<Long>()

    /** What the firer answers, by timer key; a throwable is thrown. */
    protected val answers = ConcurrentHashMap<String, Any>()

    /** The due time each send carried for its slot, as its target reads it. */
    protected val sentDue = CopyOnWriteArrayList<Long>()

    /** A send of a timer of that key waits for its gate before it answers. */
    protected val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** A send of a timer of that key takes that long on the clock before it answers. */
    protected val takes = ConcurrentHashMap<String, Duration>()

    protected val firer = TimerFirer { timer ->
        fired += timer.id
        sentDue += timer.context.due
        firedAt += System.nanoTime()
        takes[timer.key]?.let(clock::advance)
        gates[timer.key]?.await()
        when (val answer = answers[timer.key] ?: TimerOutcomes.COMMITTED) {
            is Throwable -> throw answer
            else -> answer as String
        }
    }
    protected val loops = CopyOnWriteArrayList<TimerLoop>()
    protected val runners = CopyOnWriteArrayList<TimerRunner>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        runners.forEach { it.stop() }
        loops.forEach { it.close() }
    }

    protected fun loop(
        expiresAfter: Duration? = null,
        maxAttempts: Int = 3,
        maxFireRate: Double? = null,
        pollInterval: Duration = 5.milliseconds,
        maxBusyRetries: Int = 20,
        fatals: NodeFatals = NodeFatals.NONE,
        parallelism: Int = TimerOptions().parallelism,
    ): TimerLoop = TimerLoop(
        TimerOptions(
            pollInterval = pollInterval,
            parallelism = parallelism,
            lookahead = 1.seconds,
            retry = RetryPolicy(maxAttempts = maxAttempts, initialBackoff = 1.seconds, maxBackoff = 1.minutes),
            expiresAfter = expiresAfter,
            maxFireRate = maxFireRate,
            claimTimeout = CLAIM,
            maxBusyRetries = maxBusyRetries,
        ),
        clock,
        fatals = fatals,
    ).also { loops += it }

    protected fun runner(
        expiresAfter: Duration? = null,
        timers: TimerStore = store,
        loop: TimerLoop = loop(expiresAfter),
    ): TimerRunner = TimerRunner(SHOP, timers, ledger, firer, loop).also { runners += it }

    /**
     * Waits until [loop] has run [count] more rounds that read the stores ([TimerLoop.rounds], counted as a round
     * starts): each round but the last has read the timers and dispatched what was due by the clock as the test left
     * it.
     */
    protected suspend fun rounds(count: Int, loop: TimerLoop = loops.single()) {
        val from = loop.rounds
        eventually { loop.rounds >= from + count }
    }

    protected fun timer(key: String, dueIn: Duration = Duration.ZERO, partition: Int = 0): Timer {
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

    protected suspend fun status(key: String): Timer = checkNotNull(store.find(order("o-1"), key))

    protected companion object {
        const val BACKLOG = 6
        const val RATE = 20.0
        const val TOLERANCE = 30L
        val CLAIM = 2.seconds
    }
}
