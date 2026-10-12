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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.handledResult
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.time.Clock

/**
 * One subscription in one partition: takes the partition's entries in sequence order, runs them one at a time per
 * aggregate and in parallel across aggregates, and keeps the checkpoint as the contiguous low watermark of what it
 * completed (design 04 §1, §2).
 *
 * Entries are fed by one reader at a time (the shared tail or this worker's catch-up read, never both), so
 * [cursor] only moves forward and an entry at or below it is a duplicate and dropped. Every taken entry holds a slot
 * in `pending` until it and everything before it are complete; [watermark] is the last slot popped. The slots
 * hold one permit each of `permits`, which bounds the entries between the watermark and the cursor.
 *
 * Each aggregate with entries in flight has a lane: a queue a coroutine drains in order, created when the first
 * entry arrives and removed when it runs empty.
 *
 * State touched from several coroutines is guarded by this object's monitor; the sections under it never suspend.
 *
 * When an entry carries a wait context whose next hop is this subscription, the worker signals the waiting node
 * once the entry is done with (design 04 §6.3): handled, or passed over by the filter, SUCCEEDED; parked, FAILED. The
 * signal is a side path: it never fails the entry nor holds the checkpoint.
 */
internal class SubscriptionWorker(
    private val group: String,
    val subscription: Subscription,
    val partition: Int,
    private val log: EventLog,
    private val checkpoints: CheckpointStore,
    private val ledger: FailureLedger,
    private val options: SubscriptionOptions,
    private val clock: Clock,
    private val lanes: CoroutineScope,
    private val signals: SignalSender,
    checkpoint: Long,
    parked: Map<AggregateRef, Int>,
) {
    private val permits = Semaphore(options.maxInFlight)
    private val pending = ArrayDeque<Slot>()
    private val outstanding = MutableStateFlow(0)
    private val active = HashMap<AggregateRef, Lane>()
    private val parkStates = HashMap<AggregateRef, ParkState>(parked.mapValues { ParkState.Parked(it.value) })

    /** Per aggregate, the last version [resume] handled: a later copy of one of those streams is skipped. */
    private val resumedThrough = HashMap<AggregateRef, Int>()
    private val flushLock = Mutex()
    private var saved = checkpoint

    /** Set by [abandon] under the monitor: the lanes are cancelled, so a resume would never run. */
    private var abandoned = false

    /** The sequence of the last entry taken. */
    @Volatile
    var cursor: Long = checkpoint
        private set

    /** The highest sequence such that every entry at or below it is complete. */
    @Volatile
    var watermark: Long = checkpoint
        private set

    /** Takes [entry] if there is room, without waiting; false when the in-flight bound is reached. */
    fun tryAccept(entry: LogEntry): Boolean {
        if (entry.position.sequence <= cursor) {
            return true
        }
        if (!permits.tryAcquire()) {
            return false
        }
        admit(entry)
        return true
    }

    /** Takes [entry], waiting for room. */
    suspend fun accept(entry: LogEntry) {
        if (entry.position.sequence <= cursor) {
            return
        }
        permits.acquire()
        admit(entry)
    }

    private fun admit(entry: LogEntry) = synchronized(this) {
        val stream = entry.stream
        cursor = entry.position.sequence
        val slot = Slot(entry.position.sequence)
        pending.addLast(slot)
        outstanding.value = pending.size
        if (!subscription.filter.matches(stream) || skippedOnAdmit(stream)) {
            complete(slot)
            if (waitsHere(entry)) {
                val parked = parkedAt(stream)
                lanes.launch { signal(entry, Result.success(emptyList()), parked) }
            }
        } else {
            enqueue(stream.ref, Item.Live(entry, slot), first = false)
        }
    }

    /** A parked aggregate's entries from its parked version on, and copies of what a resume handled, are skipped. */
    private fun skippedOnAdmit(stream: EventStreamRecord): Boolean {
        val state = parkStates[stream.ref]
        if (parkedAt(stream)) {
            return true
        }
        val resumed = resumedThrough[stream.ref]
        return state == null && resumed != null && stream.version <= resumed
    }

    /** Called under the monitor: whether [stream]'s aggregate is parked at or before its version. */
    private fun parkedAt(stream: EventStreamRecord): Boolean {
        val state = parkStates[stream.ref]
        return state is ParkState.Parked && stream.version >= state.fromVersion
    }

    /** Whether a wait on [entry] waits for this subscription next. */
    private fun waitsHere(entry: LogEntry): Boolean {
        val hop = entry.transport?.wait?.nextHandled ?: return false
        return hop.subscription == subscription.name && (hop.context ?: subscription.filter.context) ==
            subscription.filter.context
    }

    /**
     * Signals the wait on [entry], if it waits for this subscription next and is not over: SUCCEEDED with the
     * commands [handled] derived, or FAILED when it failed or the aggregate is [parked].
     */
    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the entry is done with.
    private suspend fun signal(entry: LogEntry, handled: Result<List<String>>, parked: Boolean = false) {
        if (!waitsHere(entry)) {
            return
        }
        val wait = checkNotNull(entry.transport?.wait)
        val now = clock.millis()
        if (wait.expiredAt(now)) {
            return
        }
        val failure = handled.exceptionOrNull()
        val error = when {
            failure != null -> ErrorInfo(ResultCodes.SUBSCRIPTION_PARKED, reason(failure))
            parked -> ErrorInfo(
                ResultCodes.SUBSCRIPTION_PARKED,
                "${entry.stream.ref} is parked by ${subscription.name}."
            )
            else -> null
        }
        val hop = checkNotNull(wait.nextHandled)
        val result = handledResult(entry.stream, hop, subscription.function, now, handled.getOrNull().orEmpty(), error)
        try {
            signals.send(wait.origin, WaitSignal(waitId = wait.waitId, result = result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to signal wait ${wait.waitId} from ${subscription.name}." }
        }
    }

    /** Called under the monitor. */
    private fun enqueue(ref: AggregateRef, item: Item, first: Boolean) {
        val lane = active[ref] ?: Lane().also {
            active[ref] = it
            lanes.launch { drain(ref, it) }
        }
        if (first) lane.items.addFirst(item) else lane.items.addLast(item)
    }

    /** Called under the monitor. */
    private fun complete(slot: Slot) {
        slot.done = true
        while (pending.firstOrNull()?.done == true) {
            watermark = pending.removeFirst().sequence
            permits.release()
        }
        outstanding.value = pending.size
    }

    private suspend fun drain(ref: AggregateRef, lane: Lane) {
        while (true) {
            val item = synchronized(this) {
                lane.items.removeFirstOrNull() ?: run {
                    active.remove(ref)
                    null
                }
            } ?: return
            when (item) {
                is Item.Live -> if (subscription.coalesces) runBatch(item, lane) else runLive(item)
                is Item.Resume -> runResume(ref, item)
            }
        }
    }

    private suspend fun runLive(item: Item.Live) {
        val entry = item.entry
        val stream = entry.stream
        val (skipped, parked) = synchronized(this) { skippedInLane(stream) to parkedAt(stream) }
        val handled = if (skipped) Result.success(emptyList()) else handle(stream, entry.position, entry.transport)
        handled.exceptionOrNull()?.let { park(stream, it as Exception) }
        synchronized(this) { complete(item.slot) }
        signal(entry, handled, skipped && parked)
    }

    /**
     * Runs [first] and the live entries queued behind it in its [lane] as one batch, for a subscription that
     * [Subscription.coalesces]: the handler gets only the latest entry it does not skip. When it fails, the aggregate
     * is parked from the earliest such entry, since none of them is reflected. Then every entry of the batch
     * completes and is signalled.
     */
    private suspend fun runBatch(first: Item.Live, lane: Lane) {
        val batch = synchronized(this) {
            val items = arrayListOf(first)
            while (lane.items.firstOrNull() is Item.Live) {
                items += lane.items.removeFirst() as Item.Live
            }
            // In version order, as skippedInLane expects.
            items.map { Batched(it, skippedInLane(it.entry.stream), parkedAt(it.entry.stream)) }
        }
        val handled = batch.filterNot { it.skipped }
        val result = handled.lastOrNull()?.let { latest ->
            val entry = latest.item.entry
            handle(entry.stream, entry.position, entry.transport)
        } ?: Result.success(emptyList())
        result.exceptionOrNull()?.let { park(handled.first().item.entry.stream, it as Exception) }
        synchronized(this) { batch.forEach { complete(it.item.slot) } }
        batch.forEach {
            if (it.skipped) {
                signal(it.item.entry, Result.success(emptyList()), it.parked)
            } else {
                signal(it.item.entry, result)
            }
        }
    }

    private class Batched(val item: Item.Live, val skipped: Boolean, val parked: Boolean)

    /** Called under the monitor. Within a lane, entries arrive in version order. */
    private fun skippedInLane(stream: EventStreamRecord): Boolean {
        val state = parkStates[stream.ref]
        if (state is ParkState.Parked && stream.version >= state.fromVersion) {
            return true
        }
        val resumed = resumedThrough[stream.ref] ?: return false
        if (stream.version <= resumed) {
            return true
        }
        // Every later stream of the aggregate is newer than what the resume read.
        resumedThrough.remove(stream.ref)
        return false
    }

    /**
     * Runs the handler with retries; returns the ids of the commands it derived, or the last failure once the
     * retries are spent.
     */
    @Suppress("TooGenericExceptionCaught") // A handler may fail with anything; only cancellation stops the retries.
    private suspend fun handle(
        stream: EventStreamRecord,
        position: LogPosition?,
        transport: Transport?,
    ): Result<List<String>> {
        var failures = 0
        while (true) {
            try {
                return Result.success(subscription.handle(subscription.decoder.decode(stream, position, transport)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures++
                if (failures >= options.retry.maxAttempts) {
                    return Result.failure(failure)
                }
                delay(options.retry.backoff(failures))
            }
        }
    }

    /** Parks the aggregate at [stream]'s version; its later entries are skipped until the ledger entry is resolved. */
    private suspend fun park(stream: EventStreamRecord, failure: Exception) {
        val entry = ParkedAggregate(subscription.name, stream.ref, stream.version, reason(failure), clock.millis())
        logger.warn(failure) {
            "Subscription ${subscription.name} parks ${stream.ref} from version ${stream.version} after " +
                "${options.retry.maxAttempts} attempts."
        }
        retryForever(options.readRetry, { "park ${stream.ref} for ${subscription.name}" }) { ledger.park(entry) }
        synchronized(this) {
            val state = parkStates[stream.ref]
            if (state !is ParkState.Resuming) {
                val from = (state as? ParkState.Parked)?.fromVersion?.coerceAtMost(stream.version) ?: stream.version
                parkStates[stream.ref] = ParkState.Parked(from)
            }
        }
    }

    /**
     * Handles [ref]'s streams again from its parked version and resolves its ledger entry (design 04 §2).
     *
     * The resume runs in the aggregate's lane, ahead of everything queued there, and the aggregate is marked
     * resuming in the same step, so from then on its new entries queue behind the resume instead of being skipped.
     * The resume reads every stream committed by the time it runs; an entry of one of those streams that the tail
     * delivers later is skipped as a copy, and every newer one is handled after the resume, in version order.
     *
     * @return true when the aggregate was handled up to its last stream and is no longer parked; false when it was
     * not parked, or failed again and stays parked from where it was.
     */
    suspend fun resume(ref: AggregateRef): Boolean {
        val entry = ledger.parked(subscription.name, ref)
        val result = synchronized(this) {
            check(!abandoned) { "Subscription ${subscription.name} stopped in partition $partition." }
            when (val state = parkStates[ref]) {
                is ParkState.Resuming -> state.result
                else -> {
                    if (entry == null) {
                        parkStates.remove(ref)
                        return false
                    }
                    val from = (state as? ParkState.Parked)?.fromVersion?.coerceAtMost(entry.fromVersion)
                        ?: entry.fromVersion
                    val item = Item.Resume(from, CompletableDeferred())
                    parkStates[ref] = ParkState.Resuming(item.result)
                    enqueue(ref, item, first = true)
                    item.result
                }
            }
        }
        return result.await()
    }

    @Suppress("TooGenericExceptionCaught") // The failure goes to the caller of resume; the aggregate stays parked.
    private suspend fun runResume(ref: AggregateRef, item: Item.Resume) {
        var from = item.fromVersion
        try {
            // A lower entry may have failed and been parked while this resume waited in the lane.
            ledger.parked(subscription.name, ref)?.let { from = minOf(from, it.fromVersion) }
            var last: Int? = null
            for ((position, stream, transport) in log.readEntries(ref, from).toList()) {
                val failure = if (subscription.filter.matches(stream)) {
                    handle(stream, position, transport).exceptionOrNull() as Exception?
                } else {
                    null
                }
                if (failure != null) {
                    // Still failing: the streams before this one are handled, so the aggregate stays parked from
                    // this one, with the new reason; a resolve that raced it leaves nothing to update.
                    from = stream.version
                    repark(stream, failure)
                    if (last != null) {
                        // Copies of the streams handled here that the tail delivers later are skipped.
                        synchronized(this) { resumedThrough[ref] = last }
                    }
                    restoreParked(ref, item, from)
                    item.result.complete(false)
                    return
                }
                last = stream.version
            }
            ledger.resolve(subscription.name, ref)
            synchronized(this) {
                parkStates.remove(ref)
                if (last != null) {
                    resumedThrough[ref] = last
                }
            }
            item.result.complete(true)
        } catch (cancelled: CancellationException) {
            restoreParked(ref, item, from)
            item.result.cancel(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            restoreParked(ref, item, from)
            item.result.completeExceptionally(failure)
        }
    }

    /** Records in the ledger that a resume failed again at [stream], from where the aggregate is now parked. */
    private suspend fun repark(stream: EventStreamRecord, failure: Exception) {
        val entry = ParkedAggregate(subscription.name, stream.ref, stream.version, reason(failure), clock.millis())
        logger.warn(failure) {
            "Subscription ${subscription.name} keeps ${stream.ref} parked, now from version ${stream.version}."
        }
        retryForever(options.readRetry, { "repark ${stream.ref} for ${subscription.name}" }) { ledger.repark(entry) }
    }

    private fun restoreParked(ref: AggregateRef, item: Item.Resume, from: Int) = synchronized(this) {
        if ((parkStates[ref] as? ParkState.Resuming)?.result === item.result) {
            parkStates[ref] = ParkState.Parked(from)
        }
    }

    /**
     * Resolves [ref]'s ledger entry without handling what was skipped: from now on its new entries are handled.
     *
     * @return true when the aggregate was parked; false when it was not, or a resume of it is running.
     */
    suspend fun skip(ref: AggregateRef): Boolean {
        if (synchronized(this) { parkStates[ref] is ParkState.Resuming }) {
            return false
        }
        val removed = ledger.resolve(subscription.name, ref)
        synchronized(this) {
            if (parkStates[ref] is ParkState.Parked) {
                parkStates.remove(ref)
            }
        }
        return removed != null
    }

    /** Saves the watermark if it moved since the last save. */
    suspend fun flush() = flushLock.withLock {
        val position = watermark
        if (position > saved) {
            checkpoints.save(group, subscription.name, partition, position)
            saved = position
        }
    }

    /** Waits until every taken entry is complete. */
    suspend fun awaitDrained() {
        outstanding.first { it == 0 }
    }

    /** Fails the resumes still queued: their lanes were cancelled by a stop. */
    fun abandon() = synchronized(this) {
        abandoned = true
        active.values.flatMap { it.items }.filterIsInstance<Item.Resume>().forEach {
            it.result.cancel(CancellationException("The subscription runtime stopped."))
        }
        active.clear()
    }

    private fun reason(failure: Throwable): String = "${failure.javaClass.name}: ${failure.message}"

    private class Slot(val sequence: Long) {
        var done = false
    }

    private class Lane {
        val items = ArrayDeque<Item>()
    }

    private sealed interface Item {
        class Live(val entry: LogEntry, val slot: Slot) : Item
        class Resume(val fromVersion: Int, val result: CompletableDeferred<Boolean>) : Item
    }

    private sealed interface ParkState {
        class Parked(val fromVersion: Int) : ParkState
        class Resuming(val result: CompletableDeferred<Boolean>) : ParkState
    }
}

private val logger = KotlinLogging.logger {}
