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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import java.time.Clock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The one firing loop of a node (decision D198): it fires the timers of every [TimerRunner] registered with it, each a
 * context's log group, in the partitions the node owns there.
 *
 * **A round**: first it writes what the timers fired since the last round came to ([TimerRunner.flush], batched
 * conditional writes); then, every [TimerOptions.pollInterval], it reads each runner's timers due within
 * [TimerOptions.lookahead] with one query over all the partitions it owns ([me.ahoo.wow.spi.timer.TimerStore.due]),
 * whatever their number; then it sends each loaded timer that is due ([TimerRunner.dispatch]), at most
 * [TimerOptions.parallelism] at once in a partition and at most [TimerOptions.maxFireRate] a second in a partition,
 * so a backlog after an outage drains at that rate. It sleeps until the next poll, the next due timer or the next
 * permitted firing, and wakes early when a firing ends or a partition starts.
 *
 * Firing only by the owner of the timer's partition, its owner's: a runner holds the partitions the node owns, from their
 * acquisition until their hand-off ([me.ahoo.wow.runtime.cluster.PartitionOwnership]).
 *
 * [rounds] counts the rounds that read the stores, for the tests of the one query per round.
 */
@InternalWowApi
class TimerLoop(
    internal val options: TimerOptions = TimerOptions(),
    internal val clock: Clock = Clock.systemUTC(),
    context: CoroutineContext = Dispatchers.Default,
) {
    private val root = SupervisorJob()
    internal val scope = CoroutineScope(context + root)
    private val runners = CopyOnWriteArraySet<TimerRunner>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = Mutex()
    private var running: Job? = null
    private val polls = AtomicLong()

    /** The rounds that read the timer stores so far. */
    val rounds: Long get() = polls.get()

    /** Fires [runner]'s timers from now on, starting the loop when it is the first. */
    internal suspend fun register(runner: TimerRunner) = lifecycle.withLock {
        runners += runner
        if (running == null) {
            running = scope.launch { run() }
        }
        wakeUp()
    }

    /** Stops firing [runner]'s timers. */
    internal fun unregister(runner: TimerRunner) {
        runners -= runner
    }

    /** Asks for a round at once: a partition started, or a firing ended. */
    internal fun wakeUp() {
        wake.trySend(Unit)
    }

    /** Stops the loop and every firing. */
    suspend fun close() = lifecycle.withLock {
        running?.cancelAndJoin()
        running = null
        root.cancel()
    }

    private suspend fun run() {
        var polled: TimeMark? = null
        while (scope.isActive) {
            runners.forEach { it.flush() }
            if (polled == null || polled.elapsedNow() >= options.pollInterval) {
                poll()
                polled = TimeSource.Monotonic.markNow()
            }
            val now = clock.millis()
            val untilNext = runners.minOfOrNull { it.dispatch(now) } ?: Duration.INFINITE
            val untilPoll = options.pollInterval - polled.elapsedNow()
            withTimeoutOrNull(minOf(untilPoll, untilNext).coerceAtLeast(TICK)) { wake.receive() }
        }
    }

    /** One query per runner that owns partitions: its timers due within the lookahead. */
    private suspend fun poll() {
        polls.incrementAndGet()
        val until = clock.millis() + options.lookahead.inWholeMilliseconds
        runners.forEach { runner ->
            val owned = runner.partitions
            if (owned.isEmpty()) {
                return@forEach
            }
            val due = quietly("read the timers of ${runner.context}") { runner.store.due(owned, until, options.batch) }
            due?.let(runner::load)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed read is read again on the next poll.
    private suspend fun <T> quietly(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Failed to $action; retrying on the next poll." }
        null
    }

    private companion object {
        val logger = KotlinLogging.logger {}
        val TICK = 1.milliseconds
    }
}
