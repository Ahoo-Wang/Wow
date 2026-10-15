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
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.handledResult
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
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
 * once the entry is done with (design 04 §6.3): handled, or passed over by the filter, SUCCEEDED; parked, FAILED; a
 * rejection the handler met ([HandledEntry.rejection], [HandlingRejectedException]; decision D153), REJECTED with its
 * error, whether it handled it or parked. The signal is a side path: it never fails the entry nor holds the
 * checkpoint.
 *
 * A handler that fails is retried, then parks its aggregate; one that fails with a [HandlingRejectedException], a
 * business outcome, parks it at once.
 *
 * The [source] is the context's log or another context's topic (decision D134). A park records where the entry lies
 * in a topic ([EntrySource.sourcePosition]), since a topic is not read by aggregate: a resume re-scans from there,
 * and one whose position the topic no longer retains marks the entry unrecoverable ([ParkedAggregate.UNRECOVERABLE]),
 * so it can only be skipped. A topic may deliver a record twice ([EntrySource.repeats]): the worker drops a copy of
 * an aggregate's version it handled already, as long as it remembers the aggregate ([REMEMBERED] of them, the most
 * recently handled).
 *
 * An aggregate's entries are compared by their ordinal ([EntrySource.ordinalOf]): their version, or, in a topic of
 * commands or receipts (decisions D135–D137), whose entries commit nothing, their offset. Parking, copies and
 * resumes go by it.
 *
 * Each handling of an entry, its retries included, is observed by [observer] (decision D174), in the trace the entry
 * carries ([me.ahoo.wow.spi.log.Transport.trace]): what the handler does goes on in that trace.
 */
internal class SubscriptionWorker(
    private val group: String,
    val subscription: Subscription,
    val partition: Int,
    private val source: EntrySource,
    private val checkpoints: CheckpointStore,
    private val ledger: FailureLedger,
    private val options: SubscriptionOptions,
    private val clock: Clock,
    private val lanes: CoroutineScope,
    private val signals: SignalSender,
    checkpoint: Long,
    parked: Map<AggregateRef, Long>,
    private val notifier: ParkedNotifier = ParkedNotifier.NONE,
    private val observer: Observer = Observer.NONE,
) {
    private val permits = Semaphore(options.maxInFlight)

    /** How the handler is retried before its aggregate is parked: its subscription's budget (roadmap P.3). */
    private val retry: RetryPolicy = options.retryOf(subscription)
    private val pending = ArrayDeque<Slot>()
    private val outstanding = MutableStateFlow(0)
    private val active = HashMap<AggregateRef, Lane>()
    private val parkStates = HashMap<AggregateRef, ParkState>(parked.mapValues { ParkState.Parked(it.value) })

    /** Per aggregate, the ordinal of the last entry [resume] handled: a later copy of one of those is skipped. */
    private val resumedThrough = HashMap<AggregateRef, Long>()

    /** Of a source that [EntrySource.repeats], the last ordinal handled of the aggregates handled most recently. */
    private val handledThrough: LinkedHashMap<AggregateRef, Long>? = if (source.repeats) {
        object : LinkedHashMap<AggregateRef, Long>(REMEMBERED, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, Long>?): Boolean =
                size > REMEMBERED
        }
    } else {
        null
    }
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

    /** The entries complete above the [watermark], each waiting for an earlier one. Guarded by the monitor. */
    private var completeAbove = 0L

    /**
     * How far the worker has come, every complete entry counted at once: the [watermark] plus the entries complete
     * above it. It grows with each entry completed, out of order too (an entry popped with the watermark adds at
     * least what it counted above it), and is never below the watermark.
     */
    val progress: Long
        get() = synchronized(this) { watermark + completeAbove }

    /** Takes [entry] if there is room, without waiting; false when the in-flight bound is reached. */
    fun tryAccept(entry: SourcedEntry): Boolean {
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
    suspend fun accept(entry: SourcedEntry) {
        if (entry.position.sequence <= cursor) {
            return
        }
        permits.acquire()
        admit(entry)
    }

    private fun admit(entry: SourcedEntry) = synchronized(this) {
        val stream = entry.stream
        cursor = entry.position.sequence
        val slot = Slot(entry.position.sequence)
        pending.addLast(slot)
        outstanding.value = pending.size
        if (!subscription.takes(stream) || skippedOnAdmit(entry)) {
            complete(slot)
            if (waitsHere(entry)) {
                val parked = parkedAt(entry)
                lanes.launch { signal(entry, Result.success(HandledEntry.NOTHING), parked) }
            }
        } else {
            enqueue(stream.ref, Item.Live(entry, slot), first = false)
        }
    }

    /** A parked aggregate's entries from its parked one on, and copies of what a resume handled, are skipped. */
    private fun skippedOnAdmit(entry: SourcedEntry): Boolean {
        val ref = entry.stream.ref
        val state = parkStates[ref]
        if (parkedAt(entry)) {
            return true
        }
        val resumed = resumedThrough[ref]
        return state == null && resumed != null && source.ordinalOf(entry) <= resumed
    }

    /** Called under the monitor: whether [entry]'s aggregate is parked at or before it. */
    private fun parkedAt(entry: SourcedEntry): Boolean {
        val state = parkStates[entry.stream.ref]
        return state is ParkState.Parked && source.ordinalOf(entry) >= state.fromOrdinal
    }

    /** Whether a wait on [entry] waits for this subscription next. */
    private fun waitsHere(entry: SourcedEntry): Boolean {
        val hop = entry.transport?.wait?.nextHandled ?: return false
        return hop.subscription == subscription.hopOf(entry.stream.ref.type) &&
            (hop.context ?: subscription.context) == subscription.context
    }

    /**
     * Signals the wait on [entry], if it waits for this subscription next and is not over: SUCCEEDED with the
     * commands [handled] derived, REJECTED with the rejection it met, or FAILED when it failed or the aggregate is
     * [parked].
     */
    @Suppress("TooGenericExceptionCaught") // A signal is best effort: whatever fails, the entry is done with.
    private suspend fun signal(entry: SourcedEntry, handled: Result<HandledEntry>, parked: Boolean = false) {
        if (!waitsHere(entry)) {
            return
        }
        val wait = checkNotNull(entry.transport?.wait)
        val now = clock.millis()
        if (wait.expiredAt(now)) {
            return
        }
        val failure = handled.exceptionOrNull()
        val rejection = (failure as? HandlingRejectedException)?.error ?: handled.getOrNull()?.rejection
        val error = when {
            rejection != null -> rejection
            failure != null -> ErrorInfo(ResultCodes.SUBSCRIPTION_PARKED, reason(failure))
            parked -> ErrorInfo(
                ResultCodes.SUBSCRIPTION_PARKED,
                "${entry.stream.ref} is parked by ${subscription.name}."
            )
            else -> null
        }
        val hop = checkNotNull(wait.nextHandled)
        val result = handledResult(
            entry.stream,
            hop,
            subscription.function,
            now,
            handled.getOrNull()?.derived.orEmpty(),
            error,
        ).let { if (rejection != null) it.copyWith(status = CommandResult.Status.REJECTED) else it }
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
        completeAbove++
        while (pending.firstOrNull()?.done == true) {
            watermark = pending.removeFirst().sequence
            completeAbove--
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
        val (skipped, parked) = synchronized(this) { skippedInLane(entry) to parkedAt(entry) }
        val handled = if (skipped) {
            Result.success(HandledEntry.NOTHING)
        } else {
            handle(entry)
        }
        handled.exceptionOrNull()?.let { park(entry, it as Exception) }
        synchronized(this) {
            if (!skipped && handled.isSuccess) {
                remember(entry)
            }
            complete(item.slot)
        }
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
            items.map { Batched(it, skippedInLane(it.entry), parkedAt(it.entry)) }
        }
        val handled = batch.filterNot { it.skipped }
        val result = handled.lastOrNull()?.let { latest -> handle(latest.item.entry) } ?: Result.success(HandledEntry.NOTHING)
        result.exceptionOrNull()?.let { park(handled.first().item.entry, it as Exception) }
        synchronized(this) {
            if (result.isSuccess) {
                handled.forEach { remember(it.item.entry) }
            }
            batch.forEach { complete(it.item.slot) }
        }
        batch.forEach {
            if (it.skipped) {
                signal(it.item.entry, Result.success(HandledEntry.NOTHING), it.parked)
            } else {
                signal(it.item.entry, result)
            }
        }
    }

    private class Batched(val item: Item.Live, val skipped: Boolean, val parked: Boolean)

    /**
     * Called under the monitor. Within a lane, entries arrive in their ordinal order ([EntrySource.ordinalOf]), except
     * a copy a source that [EntrySource.repeats] delivers again: an ordinal at or below the last one handled of the
     * aggregate.
     */
    private fun skippedInLane(entry: SourcedEntry): Boolean {
        val ref = entry.stream.ref
        val ordinal = source.ordinalOf(entry)
        val state = parkStates[ref]
        if (state is ParkState.Parked && ordinal >= state.fromOrdinal) {
            return true
        }
        val handled = handledThrough?.get(ref)
        if (handled != null && ordinal <= handled) {
            return true
        }
        val resumed = resumedThrough[ref] ?: return false
        if (ordinal <= resumed) {
            return true
        }
        // Every later entry of the aggregate is newer than what the resume read.
        resumedThrough.remove(ref)
        return false
    }

    /** Called under the monitor: [entry] is handled, so a copy of it, or of an earlier one, is dropped. */
    private fun remember(entry: SourcedEntry) {
        handledThrough?.merge(entry.stream.ref, source.ordinalOf(entry), ::maxOf)
    }

    /**
     * Runs the handler with its subscription's retries ([retry]); returns what it did, or the last failure once the
     * retries are spent. An attempt that outlasts the budget's timeout is cancelled and fails with a
     * [HandlerTimeoutException]. A [HandlingRejectedException] is not retried: a business outcome does not change.
     */
    private suspend fun handle(sourced: SourcedEntry): Result<HandledEntry> {
        val stream = sourced.stream
        val trace = sourced.transport?.trace.orEmpty()
        val observed = observer.handling(subscription.name, subscription.context, stream, trace, source.topic)
        return observed.observe {
            retried(sourced).also { handled -> handled.exceptionOrNull()?.let { observed.failed(it) } }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A handler may fail with anything; only cancellation stops the retries.
    private suspend fun retried(sourced: SourcedEntry): Result<HandledEntry> {
        var failures = 0
        while (true) {
            try {
                return Result.success(attempt(sourced))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: HandlingRejectedException) {
                return Result.failure(rejected)
            } catch (failure: Exception) {
                failures++
                if (failures >= retry.maxAttempts) {
                    return Result.failure(failure)
                }
                delay(retry.backoff(failures))
            }
        }
    }

    /** One attempt of the handler, within the budget's timeout when it has one. */
    private suspend fun attempt(sourced: SourcedEntry): HandledEntry {
        if (retry.timeout.isInfinite()) {
            return decodeAndHandle(sourced)
        }
        return withTimeoutOrNull(retry.timeout) { decodeAndHandle(sourced) }
            ?: throw HandlerTimeoutException(subscription.name, sourced.stream.ref, sourced.stream.version, retry.timeout)
    }

    private suspend fun decodeAndHandle(sourced: SourcedEntry): HandledEntry {
        val decoded = subscription.decoder.decode(sourced.stream, sourced.position, sourced.transport)
        val entry = if (sourced.published == null && sourced.message == null) {
            decoded
        } else {
            decoded.copy(published = sourced.published, message = sourced.message)
        }
        return subscription.handle(entry)
    }

    /**
     * Parks the aggregate at [sourced]'s version, and at its position in a topic; its later entries are skipped until
     * the ledger entry is resolved.
     */
    private suspend fun park(sourced: SourcedEntry, failure: Exception) {
        val stream = sourced.stream
        val entry = ParkedAggregate(
            subscription.name,
            stream.ref,
            stream.version,
            reason(failure),
            clock.millis(),
            source.sourcePosition(sourced),
        )
        logger.warn(failure) {
            val after = if (failure is HandlingRejectedException) "a rejection" else "${retry.maxAttempts} attempts"
            "Subscription ${subscription.name} parks ${stream.ref} from version ${stream.version} after $after."
        }
        val changed = retryForever(options.readRetry, { "park ${stream.ref} for ${subscription.name}" }) {
            ledger.park(entry)
        }
        if (changed && notifier !== ParkedNotifier.NONE) {
            val notice = ParkedNotice(
                ParkedNotice.Kind.PARKED,
                group,
                entry,
                options.parked.specOf(entry),
                entry.failedAt,
            )
            lanes.launch { notifier.notifyQuietly(notice) }
        }
        synchronized(this) {
            val state = parkStates[stream.ref]
            if (state !is ParkState.Resuming) {
                val ordinal = source.ordinalOf(sourced)
                val from = (state as? ParkState.Parked)?.fromOrdinal?.coerceAtMost(ordinal) ?: ordinal
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
                    if (entry.unrecoverable) {
                        return false
                    }
                    val item = Item.Resume(entry, CompletableDeferred())
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
        var parked = item.parked
        try {
            // A lower entry may have failed and been parked while this resume waited in the lane.
            ledger.parked(subscription.name, ref)?.takeIf { source.ordinalOf(it) < source.ordinalOf(parked) }?.let {
                parked = it
            }
            val entries = try {
                source.resume(ref, parked.fromVersion, parked.fromPosition).toList()
            } catch (gone: UnrecoverableException) {
                unrecoverable(parked, gone)
                restoreParked(ref, item, parked)
                item.result.complete(false)
                return
            }
            var last: Long? = null
            for (entry in entries) {
                val ordinal = source.ordinalOf(entry)
                if (last != null && ordinal <= last) {
                    // A copy the topic holds twice.
                    continue
                }
                val failure = if (subscription.takes(entry.stream)) {
                    handle(entry).exceptionOrNull() as Exception?
                } else {
                    null
                }
                if (failure != null) {
                    // Still failing: the entries before this one are handled, so the aggregate stays parked from
                    // this one, with the new reason; a resolve that raced it leaves nothing to update.
                    parked = repark(entry, failure)
                    if (last != null) {
                        // Copies of the entries handled here that the tail delivers later are skipped.
                        synchronized(this) { resumedThrough[ref] = last }
                    }
                    restoreParked(ref, item, parked)
                    item.result.complete(false)
                    return
                }
                last = ordinal
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
            restoreParked(ref, item, parked)
            item.result.cancel(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            restoreParked(ref, item, parked)
            item.result.completeExceptionally(failure)
        }
    }

    /**
     * Records in the ledger that a resume failed again at [sourced], from where the aggregate is now parked; returns
     * the entry.
     */
    private suspend fun repark(sourced: SourcedEntry, failure: Exception): ParkedAggregate {
        val stream = sourced.stream
        logger.warn(failure) {
            "Subscription ${subscription.name} keeps ${stream.ref} parked, now from version ${stream.version}."
        }
        return ParkedAggregate(
            subscription.name,
            stream.ref,
            stream.version,
            reason(failure),
            clock.millis(),
            source.sourcePosition(sourced),
        ).also { repark(it) }
    }

    /**
     * Records in the ledger that [parked]'s aggregate can no longer be resumed, only skipped: the source does not
     * hold what it is parked from any more ([gone]).
     */
    private suspend fun unrecoverable(parked: ParkedAggregate, gone: UnrecoverableException) {
        logger.error {
            "Subscription ${subscription.name} cannot resume ${parked.ref}: ${gone.message} It can only be skipped."
        }
        val reason = "${ParkedAggregate.UNRECOVERABLE}: ${gone.message}"
        repark(parked.copy(reason = reason, failedAt = clock.millis()))
    }

    private suspend fun repark(entry: ParkedAggregate) {
        retryForever(options.readRetry, { "repark ${entry.ref} for ${subscription.name}" }) { ledger.repark(entry) }
    }

    private fun restoreParked(ref: AggregateRef, item: Item.Resume, parked: ParkedAggregate) = synchronized(this) {
        if ((parkStates[ref] as? ParkState.Resuming)?.result === item.result) {
            parkStates[ref] = ParkState.Parked(source.ordinalOf(parked))
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
        class Live(val entry: SourcedEntry, val slot: Slot) : Item
        class Resume(val parked: ParkedAggregate, val result: CompletableDeferred<Boolean>) : Item
    }

    private sealed interface ParkState {
        class Parked(val fromOrdinal: Long) : ParkState
        class Resuming(val result: CompletableDeferred<Boolean>) : ParkState
    }
}

private val logger = KotlinLogging.logger {}

/** How many aggregates a worker of a source that repeats remembers the last version handled of. */
private const val REMEMBERED = 4_096
private const val LOAD_FACTOR = 0.75f
