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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Fires the timers of one log group of one bounded [context] in the partitions this node
 * owns: the node's [TimerLoop] reads them from [store] and sends them, the runner holds what it loaded and what is
 * being sent per partition ([startPartition], [stopPartition], driven by the partition leases,
 * [me.ahoo.wow.runtime.cluster.PartitionOwnership]), so a timer is sent by the owner of its owner's partition. A new
 * owner reads the store again: nothing is handed over.
 *
 * **Sending one**: from the row as loaded, with no read of it again. One due longer ago than
 * [TimerOptions.expiresAfter] is cancelled as `EXPIRED`. Otherwise the runner first claims it with one conditional
 * write from the loaded row (PENDING to FIRING, due again at the claim's deadline, [TimerOptions.claimTimeout]): a
 * timer cancelled or rescheduled after it was loaded fails the claim and is not sent. Claimed, [firer] sends it, its
 * request id `timer#slot`, and its outcome becomes a conditional change of the claimed row ([TimerChange]), written
 * with the others of the round in one batch ([flush]). A claim left by a node that died lapses at its deadline, and
 * the next owner of the partition claims and sends the timer again, deduplicated by its request id:
 *
 * - committed, already committed (a duplicate: sent before, whose outcome was not recorded), unchanged or rejected:
 *   its slot fired; a rule due once is then FIRED, a recurring one PENDING at its next slot, back in the wheel. A rejection is the target's state guard doing its work, not a
 *   failure: it is neither retried nor handed to `@OnRejected`;
 * - sent to another context's command topic: FIRED with the outcome `SENT` once the broker acknowledged it;
 * - a technical failure (a contended or unknown outcome, a failing store, no transport, a target whose clock is too
 *   far behind, [me.ahoo.wow.runtime.wait.ResultCodes.SCHEDULE_NOT_DUE]): the timer stays PENDING, due again after
 *   [TimerOptions.retry]'s backoff, until its attempts are spent; it is then PARKED and its target recorded in the
 *   failure ledger under [TimerOptions.PARKED_SUBSCRIPTION], for an operator to [retry] or [skip];
 * - refused as busy ([me.ahoo.wow.runtime.wait.ResultCodes.BUSY]): backpressure, not a failure:
 *   the timer stays PENDING, due again after [TimerOptions.retry]'s first backoff, its attempts as
 *   they were, up to [TimerOptions.maxBusyRetries] times in a row on this node; one refusal more counts as a failed
 *   attempt, so a target that stays busy parks the timer in the end, as a subscription's backpressure does.
 *
 * A claim, a retry or a retry moves only the timer's [Timer.dueAt]: every send of the slot carries the slot's own due
 * time ([Timer.slotDueAt]), so its target never decides it before then. A recurring timer whose slot is
 * past due by more than one time of its rule when it is claimed is caught up first ([ScheduleSlots.caughtUp]): a
 * coalescing rule sends only the latest slot due, the others counted as skipped; an expired slot of a recurring rule
 * moves on to its next slot instead of cancelling the timer.
 *
 * At least once, applied once: a crash after sending and before writing sends the timer again, and its target finds
 * the request id committed already.
 */
@InternalWowApi
class TimerRunner(
    val context: String,
    internal val store: TimerStore,
    internal val ledger: FailureLedger,
    private val firer: TimerFirer,
    private val loop: TimerLoop,
) {
    private val options: TimerOptions get() = loop.options
    private val parts = ConcurrentHashMap<Int, Part>()
    private val settling = ConcurrentLinkedQueue<Settling>()

    /** The partitions whose timers this runner sends now. */
    val partitions: Set<Int> get() = parts.keys.toSortedSet()

    /** Starts sending the timers of [partition], which the node has just acquired; nothing when it does already. */
    suspend fun startPartition(partition: Int) {
        parts.computeIfAbsent(partition) { Part(it) }
        loop.register(this)
    }

    /**
     * Stops sending the timers of [partition]: the node hands it off. A command being sent is cancelled; if it reached
     * its target, the new owner sends it again and the target finds it committed. What was sent is written first.
     */
    suspend fun stopPartition(partition: Int) {
        parts.remove(partition)?.job?.cancelAndJoin()
        flush()
    }

    /** Stops every partition. */
    suspend fun stop() {
        parts.keys.toList().forEach { stopPartition(it) }
        loop.unregister(this)
    }

    /**
     * Sends the PARKED timers of [target] again, due now with their attempts reset, and resolves its ledger entry: an
     * operator's retry. Any node may call it; the owner of the timers' partition sends them.
     *
     * @return how many timers were retried.
     */
    suspend fun retry(target: AggregateRef): Int {
        val now = loop.clock.millis()
        val retried = store.settle(
            store.parked(target).map {
                TimerChange(it, it.copyWith(status = TimerStatus.PENDING, attempts = 0, outcome = null, dueAt = now))
            },
        )
        ledger.resolve(TimerOptions.PARKED_SUBSCRIPTION, target)
        return retried
    }

    /**
     * Cancels the PARKED timers of [target] with the outcome `SKIPPED` and resolves its ledger entry: an operator's
     * skip.
     *
     * @return how many timers were skipped.
     */
    suspend fun skip(target: AggregateRef): Int {
        val skipped = store.settle(
            store.parked(target).map {
                TimerChange(it, it.copyWith(status = TimerStatus.CANCELLED, outcome = TimerOutcomes.SKIPPED))
            },
        )
        ledger.resolve(TimerOptions.PARKED_SUBSCRIPTION, target)
        return skipped
    }

    /** Takes [due], the timers the loop just read, into the wheel of each partition, but those being sent. */
    internal fun load(due: List<Timer>) {
        val byPartition = due.groupBy { it.partition }
        parts.values.forEach { part ->
            synchronized(part) {
                part.wheel.clear()
                byPartition[part.partition].orEmpty().filterNot { it.id in part.sending }.forEach(part.wheel::add)
            }
        }
    }

    /**
     * Sends what is due by [now] (the clock's epoch milliseconds) in each partition, as fast as the partitions'
     * parallelism and rate allow; returns how long until the next loaded timer is due or may be sent, infinite when none
     * is loaded. The rate is measured on the monotonic clock, so a clock set back or forth does not burst nor stall it.
     */
    internal fun dispatch(now: Long): Duration = parts.values.minOfOrNull { part ->
        synchronized(part) {
            while (part.wheel.isNotEmpty() && part.wheel.first().dueAt <= now && part.mayFire()) {
                val timer = part.wheel.pollFirst()!!
                if (!part.sending.add(timer.id)) {
                    continue
                }
                part.fired()
                part.scope.launch { part.permits.withPermit { fire(part, timer) } }
            }
            val first = part.wheel.firstOrNull() ?: return@synchronized Duration.INFINITE
            maxOf((first.dueAt - now).milliseconds, part.untilNextFire())
        }
    } ?: Duration.INFINITE

    /**
     * Writes the changes the timers sent since the last flush came to, in one batch: each only while the store holds
     * its timer as it was loaded. A failed write leaves them PENDING: sent again, they are deduplicated by their
     * targets.
     */
    internal suspend fun flush() {
        val batch = generateSequence { settling.poll() }.toList()
        if (batch.isEmpty()) {
            return
        }
        quietly("write the outcomes of ${batch.size} timers") { store.settle(batch.map { it.change }) }
        batch.forEach { it.part.sending.remove(it.change.loaded.id) }
        // A recurring timer's next slot goes straight back into the wheel; its claim fails if the write did not land.
        batch.forEach { offer(it.change.next) }
    }

    /**
     * Takes [timer], just written PENDING on this node, into the wheel of its partition at once, when
     * this runner sends that partition's timers and the timer is due within the loop's lookahead: the `timers`
     * subscription's writes, and a recurring timer's next slot. Its send claims it from the row as written, so a timer
     * the store does not hold so is not sent.
     */
    internal fun offer(timer: Timer) {
        if (timer.status != TimerStatus.PENDING) {
            return
        }
        val part = parts[timer.partition] ?: return
        if (timer.dueAt > loop.clock.millis() + options.lookahead.inWholeMilliseconds) {
            return
        }
        synchronized(part) {
            if (timer.id in part.sending) {
                return
            }
            part.wheel.removeIf { it.id == timer.id }
            part.wheel.add(timer)
        }
        loop.wakeUp()
    }

    /** How many timers of [partition] are loaded and not sent yet. */
    internal fun loaded(partition: Int): Int = parts[partition]?.let { synchronized(it) { it.wheel.size } } ?: 0

    /**
     * Claims [timer], due, sends it once claimed, and queues its outcome for the next [flush]. Whatever ends the attempt
     * unsettled leaves the timer to the next load, not stuck as being sent.
     */
    @Suppress("TooGenericExceptionCaught") // Rethrown once the timer is no longer marked as being sent.
    private suspend fun fire(part: Part, timer: Timer) {
        try {
            attempt(part, timer)
        } catch (fatal: VirtualMachineError) {
            part.sending.remove(timer.id)
            logger.error(fatal) { "Sending the timer ${timer.id} met a fatal error." }
            throw fatal
        } catch (escaped: Throwable) {
            part.sending.remove(timer.id)
            throw escaped
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails is a technical failure of this attempt.
    private suspend fun attempt(part: Part, timer: Timer) {
        val now = loop.clock.millis()
        // A recurring slot long past due moves on to the latest one due, as its rule's misfire policy says.
        val current = ScheduleSlots.caughtUp(timer, now)
        val expiry = options.expiresAfter
        if (expiry != null && now - current.dueAt > expiry.inWholeMilliseconds) {
            logger.warn { "The timer ${timer.id} was due ${(now - current.dueAt).milliseconds} ago; it expired." }
            val next = ScheduleSlots.afterFired(current, now)?.copyWith(outcome = TimerOutcomes.EXPIRED)
                ?: current.copyWith(status = TimerStatus.CANCELLED, outcome = TimerOutcomes.EXPIRED)
            settle(part, timer, next)
            return
        }
        // Claimed before it is sent, from the row as loaded: one cancelled or rescheduled since is not sent.
        val claimed = current.copyWith(
            status = TimerStatus.FIRING,
            dueAt = now + options.claimTimeout.inWholeMilliseconds
        )
        if (quietly("claim the timer ${timer.id}") { store.settle(listOf(TimerChange(timer, claimed))) } != 1) {
            part.sending.remove(timer.id)
            part.busy.remove(timer.id)
            return
        }
        val outcome = try {
            firer.fire(claimed)
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            // Only this runner's own cancellation stops the attempt: a cancellation the sending
            // threw itself, or an Error such as a missing class, is a failed attempt, retried and then parked.
            currentCoroutineContext().ensureActive()
            failed(part, claimed, failure, now)
            return
        }
        part.busy.remove(claimed.id)
        settle(part, claimed, fired(claimed, outcome, loop.clock.millis()))
    }

    /**
     * [timer], claimed, after its slot was sent with [outcome] at [now]: a recurring rule moves on to its next slot,
     * PENDING ([ScheduleSlots.afterFired]); a rule due once, or one with no next time, is done, due when
     * its slot was ([Timer.slotDueAt]), however many attempts or claims it took.
     */
    private fun fired(timer: Timer, outcome: String, now: Long): Timer =
        ScheduleSlots.afterFired(timer, now)?.copyWith(outcome = outcome) ?: timer.copyWith(
            status = TimerStatus.FIRED,
            attempts = timer.attempts + 1,
            outcome = outcome,
            dueAt = timer.slotDueAt
        )

    private fun settle(part: Part, loaded: Timer, next: Timer) {
        settling += Settling(part, TimerChange(loaded, next))
        loop.wakeUp()
    }

    /**
     * One more failed attempt of [timer], as claimed: PENDING again, due after the backoff, or parked once its attempts
     * are spent.
     */
    private suspend fun failed(part: Part, timer: Timer, failure: Throwable, now: Long) {
        val reason = "${failure.javaClass.name}: ${failure.message}"
        val busy = failure is CommandBusyException
        if (busy && (part.busy.merge(timer.id, 1, Int::plus) ?: 1) <= options.maxBusyRetries) {
            // Backpressure, not a failure: sent again after the first backoff, no attempt counted.
            val backoff = options.retry.initialBackoff
            logger.debug { "The target of the timer ${timer.id} is busy; sending it again in $backoff." }
            settle(
                part,
                timer,
                timer.copyWith(
                    status = TimerStatus.PENDING,
                    outcome = reason,
                    dueAt = now + backoff.inWholeMilliseconds
                ),
            )
            return
        }
        // A target still busy after every retry spends an attempt, and the count of refusals starts again.
        part.busy.remove(timer.id)
        val attempts = timer.attempts + 1
        if (attempts < options.retry.maxAttempts) {
            val backoff = options.retry.backoff(attempts)
            logger.warn(failure) { "Sending the timer ${timer.id} failed (attempt $attempts); retrying in $backoff." }
            settle(
                part,
                timer,
                timer.copyWith(
                    status = TimerStatus.PENDING,
                    attempts = attempts,
                    outcome = reason,
                    dueAt = now + backoff.inWholeMilliseconds,
                ),
            )
            return
        }
        logger.error(failure) {
            "Sending the timer ${timer.id} failed $attempts times; parking it under ${TimerOptions.PARKED_SUBSCRIPTION}."
        }
        // Written at once, so the ledger records only a timer the store holds parked.
        val parked = TimerChange(
            timer,
            timer.copyWith(status = TimerStatus.PARKED, attempts = attempts, outcome = reason)
        )
        val written = quietly("park the timer ${timer.id}") { store.settle(listOf(parked)) } == 1
        part.sending.remove(timer.id)
        if (written) {
            quietly("park the target of the timer ${timer.id}") {
                ledger.park(
                    ParkedAggregate(
                        TimerOptions.PARKED_SUBSCRIPTION,
                        timer.target,
                        timer.partition,
                        1,
                        "${timer.id}: $reason",
                        now,
                    ),
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed write is retried by the next poll.
    private suspend fun <T> quietly(action: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Failed to $action in $context." }
        null
    }

    /** One partition's timers: those loaded and not sent yet ([wheel]), and those being sent or written ([sending]). */
    private inner class Part(val partition: Int) {
        val job = SupervisorJob(loop.scope.coroutineContext[Job])
        val scope = CoroutineScope(loop.scope.coroutineContext + job)
        val wheel = TreeSet(DUE_ORDER)
        val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** How many times in a row each timer's target refused it as busy, by timer id. */
        val busy = ConcurrentHashMap<String, Int>()
        val permits = Semaphore(options.parallelism)

        /** The time between two sends at the partition's rate, none without one. */
        private val interval: Duration =
            options.maxFireRate?.let { (MILLIS_PER_SECOND / it).roundToLong().coerceAtLeast(1).milliseconds } ?: Duration.ZERO

        /** When the partition may send its next timer, by its rate. */
        private var next: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

        fun mayFire(): Boolean = !next.hasNotPassedNow()

        /** One timer sent: the next one may go [interval] after the later of now and the previous allowance. */
        fun fired() {
            next = maxOf(next, TimeSource.Monotonic.markNow()) + interval
        }

        fun untilNextFire(): Duration = (-next.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    /** A change waiting for the next [flush], of a timer of [part]. */
    private class Settling(val part: Part, val change: TimerChange)

    private companion object {
        val logger = KotlinLogging.logger {}
        val DUE_ORDER: Comparator<Timer> = compareBy<Timer>({ it.dueAt }, { it.id })
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * Sends a due timer's command and returns its outcome ([TimerOutcomes]): committed, already
 * committed, unchanged, rejected, or sent to another context. A technical failure throws.
 */
@InternalWowApi
fun interface TimerFirer {
    suspend fun fire(timer: Timer): String
}
