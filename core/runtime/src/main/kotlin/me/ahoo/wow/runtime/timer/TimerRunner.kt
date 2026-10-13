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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import java.time.Clock
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Sends the scheduled commands of one bounded [context] (decision D166) in the partitions this node owns: a loop per
 * partition, started and stopped with the partition's lease ([me.ahoo.wow.runtime.cluster.PartitionOwnership]), so a
 * timer is sent by the owner of its target's partition. A new owner reads the store again: nothing is handed over.
 *
 * **The loop** reads the PENDING timers of its partition due within [TimerOptions.lookahead] every
 * [TimerOptions.pollInterval] ([TimerStore.due]) into a wheel ordered by due time, and sends each one when it is due,
 * at most [TimerOptions.parallelism] at once. A timer overdue when it is read (an outage, a handover, a backlog) is
 * sent at once: a command is never sent early, and may be sent late.
 *
 * **Sending one** ([fire]): the timer is read again, so one cancelled or rescheduled since it was loaded is not sent;
 * one due longer ago than [TimerOptions.expiresAfter] is cancelled as `EXPIRED`. Otherwise [firer] sends it, its
 * request id the timer's id, and its outcome is recorded with a conditional update ([TimerStore.update]):
 *
 * - committed, already committed (a duplicate: sent before, whose outcome was not recorded) or unchanged: FIRED;
 * - rejected: FIRED with the outcome `REJECTED`. A rejection is the target's state guard doing its work (the order
 *   was paid before its payment timed out), not a failure: it is neither retried nor handed to `@OnRejected`;
 * - a command sent to another context's command topic: FIRED with the outcome `SENT` once the broker acknowledged it;
 *   its outcome is the target's;
 * - a technical failure (a contended or unknown outcome, a failing store, no transport): the timer stays PENDING, due
 *   again after [TimerOptions.retry]'s backoff, until its attempts are spent; it is then PARKED and its target
 *   recorded in the failure ledger under [TimerOptions.PARKED_SUBSCRIPTION], for an operator to [resume] or [skip].
 *
 * At least once, applied once: a crash after sending and before recording sends the timer again, and its target
 * finds the request id committed already.
 */
@Suppress("LongParameterList")
class TimerRunner(
    val context: String,
    private val store: TimerStore,
    private val ledger: FailureLedger,
    private val firer: TimerFirer,
    private val options: TimerOptions = TimerOptions(),
    private val clock: Clock = Clock.systemUTC(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
) {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val lifecycle = Mutex()
    private val loops = HashMap<Int, Job>()

    /** The partitions whose timers this runner sends now. */
    val partitions: Set<Int> get() = synchronized(loops) { loops.keys.toSortedSet() }

    /** Starts sending the timers of [partition], which the node has just acquired; nothing when it does already. */
    suspend fun startPartition(partition: Int): Unit = lifecycle.withLock {
        synchronized(loops) {
            if (partition !in loops) {
                loops[partition] = scope.launch { PartitionLoop(partition).run() }
            }
        }
    }

    /**
     * Stops sending the timers of [partition]: the node hands it off. A command being sent is cancelled; if it reached
     * its target, the new owner sends it again and the target finds it committed.
     */
    suspend fun stopPartition(partition: Int): Unit = lifecycle.withLock {
        synchronized(loops) { loops.remove(partition) }?.cancelAndJoin()
    }

    /** Stops every partition. */
    suspend fun stop(): Unit = lifecycle.withLock {
        val stopped = synchronized(loops) { loops.values.toList().also { loops.clear() } }
        stopped.forEach { it.cancelAndJoin() }
    }

    /**
     * Sends the PARKED timers of [target] again, due now with their attempts reset, and resolves its ledger entry: an
     * operator's resume. Any node may call it; the owner of the timers' partition sends them.
     *
     * @return how many timers were resumed.
     */
    suspend fun resume(target: AggregateRef): Int {
        val now = clock.millis()
        val resumed = store.parked(target).count {
            store.update(
                it.copy(status = TimerStatus.PENDING, attempts = 0, outcome = null, dueAt = now),
                TimerStatus.PARKED
            )
        }
        ledger.resolve(TimerOptions.PARKED_SUBSCRIPTION, target)
        return resumed
    }

    /**
     * Cancels the PARKED timers of [target] with the outcome `SKIPPED` and resolves its ledger entry: an operator's
     * skip.
     *
     * @return how many timers were skipped.
     */
    suspend fun skip(target: AggregateRef): Int {
        val skipped = store.parked(target).count {
            store.update(it.copy(status = TimerStatus.CANCELLED, outcome = TimerOutcomes.SKIPPED), TimerStatus.PARKED)
        }
        ledger.resolve(TimerOptions.PARKED_SUBSCRIPTION, target)
        return skipped
    }

    /** Sends [timer], due, if it is still PENDING as it was read, and records the outcome. */
    @Suppress("TooGenericExceptionCaught") // Whatever fails is a technical failure of this attempt.
    internal suspend fun fire(timer: Timer) {
        val now = clock.millis()
        val current = try {
            store.find(timer.owner, timer.key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to read the timer ${timer.id}; it is read again on the next poll." }
            return
        }
        if (current == null || !current.isDue(timer, now)) {
            return
        }
        val expiry = options.expiresAfter
        if (expiry != null && now - current.dueAt > expiry.inWholeMilliseconds) {
            logger.warn { "The timer ${current.id} was due ${(now - current.dueAt).milliseconds} ago; it expired." }
            record(current.copy(status = TimerStatus.CANCELLED, outcome = TimerOutcomes.EXPIRED))
            return
        }
        val outcome = try {
            firer.fire(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed(current, failure, now)
            return
        }
        record(current.copy(status = TimerStatus.FIRED, attempts = current.attempts + 1, outcome = outcome))
    }

    /** Whether this, the stored timer, is still [loaded], PENDING and due at [now]. */
    private fun Timer.isDue(loaded: Timer, now: Long): Boolean =
        id == loaded.id && status == TimerStatus.PENDING && dueAt <= now

    /** One more failed attempt of [timer]: due again after the backoff, or parked once its attempts are spent. */
    private suspend fun failed(timer: Timer, failure: Exception, now: Long) {
        val attempts = timer.attempts + 1
        val reason = "${failure.javaClass.name}: ${failure.message}"
        if (attempts < options.retry.maxAttempts) {
            val backoff = options.retry.backoff(attempts)
            logger.warn(failure) { "Sending the timer ${timer.id} failed (attempt $attempts); retrying in $backoff." }
            record(timer.copy(attempts = attempts, outcome = reason, dueAt = now + backoff.inWholeMilliseconds))
            return
        }
        logger.error(failure) {
            "Sending the timer ${timer.id} failed $attempts times; parking it under ${TimerOptions.PARKED_SUBSCRIPTION}."
        }
        if (record(timer.copy(status = TimerStatus.PARKED, attempts = attempts, outcome = reason))) {
            quietly("park the target of the timer ${timer.id}") {
                ledger.park(
                    ParkedAggregate(TimerOptions.PARKED_SUBSCRIPTION, timer.target, 1, "${timer.id}: $reason", now),
                )
            }
        }
    }

    /**
     * Records [timer]'s new status while it is still the PENDING timer it was read as; false when it is not, or the
     * store failed: then it is read again on the next poll, and a command already sent is deduplicated by its target.
     */
    private suspend fun record(timer: Timer): Boolean =
        quietly("record the outcome of the timer ${timer.id}") { store.update(timer, TimerStatus.PENDING) } == true

    @Suppress("TooGenericExceptionCaught") // A failed write is retried by the next poll.
    private suspend fun <T> quietly(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Failed to $action in $context." }
        null
    }

    /** The loop of one partition: polls the store into the wheel and sends each timer when it is due. */
    private inner class PartitionLoop(private val partition: Int) {
        private val wheel = TreeSet(DUE_ORDER)
        private val sending = ConcurrentHashMap.newKeySet<String>()
        private val permits = Semaphore(options.parallelism)

        suspend fun run() = coroutineScope {
            var polled: TimeMark? = null
            while (isActive) {
                if (polled == null || polled.elapsedNow() >= options.pollInterval) {
                    load()
                    polled = TimeSource.Monotonic.markNow()
                }
                val now = clock.millis()
                while (wheel.isNotEmpty() && wheel.first().dueAt <= now) {
                    val timer = wheel.pollFirst()!!
                    if (sending.add(timer.id)) {
                        launch {
                            try {
                                permits.withPermit { fire(timer) }
                            } finally {
                                sending.remove(timer.id)
                            }
                        }
                    }
                }
                val untilPoll = options.pollInterval - polled.elapsedNow()
                val untilDue = wheel.firstOrNull()?.let { (it.dueAt - clock.millis()).milliseconds } ?: Duration.INFINITE
                delay(minOf(untilPoll, untilDue).coerceAtLeast(TICK))
            }
        }

        /** Replaces the wheel with the timers due within the lookahead, but those being sent. */
        private suspend fun load() {
            val until = clock.millis() + options.lookahead.inWholeMilliseconds
            val due = quietly("read the timers of partition $partition") {
                store.due(partition, until, options.batch)
            } ?: return
            wheel.clear()
            due.filterNot { it.id in sending }.forEach(wheel::add)
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
        val DUE_ORDER: Comparator<Timer> = compareBy<Timer>({ it.dueAt }, { it.id })
        val TICK = 1.milliseconds
    }
}

/**
 * Sends a due timer's command (decision D166) and returns its outcome ([TimerOutcomes]): committed, already
 * committed, unchanged, rejected, or sent to another context. A technical failure throws.
 */
fun interface TimerFirer {
    suspend fun fire(timer: Timer): String
}
