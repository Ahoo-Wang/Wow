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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.observation.Observer
import java.time.Clock
import kotlin.random.Random

/**
 * One subscription in one partition: takes the partition's entries in sequence order, runs them one at a time per
 * aggregate and in parallel across aggregates, and keeps the checkpoint as the contiguous low watermark of what it
 * completed (§2).
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
 * once the entry is done with: handled, or passed over by the filter, SUCCEEDED; parked, FAILED; a
 * rejection the handler met ([HandledEntry.rejection], [HandlingRejectedException]), REJECTED with its
 * error, whether it handled it or parked. The signal is a side path: it never fails the entry nor holds the
 * checkpoint.
 *
 * A handler that fails is retried, then parks its aggregate; one that fails with a [HandlingRejectedException], a
 * business outcome, or with an unrecoverable failure, parks it at once (the latter marked
 * [Recoverable.UNRECOVERABLE], so the parked retries leave it to an operator). A systemic failure pauses the partition
 * instead ([PartitionPause]): no aggregate is charged or parked until a handling succeeds, and the
 * ledger is not written meanwhile.
 *
 * The [source] is the context's log or another context's topic. A park records where the entry lies
 * in a topic ([EntrySource.sourcePosition]), since a topic is not read by aggregate: a retry re-scans from there,
 * and one whose position the topic no longer retains marks the entry skip-only ([ParkedAggregate.skipOnly]): it is
 * never retried, by hand either, whatever an operator marked it, only skipped. A topic may deliver a record twice ([EntrySource.repeats]): the worker drops a copy of
 * an aggregate's version it handled already, as long as it remembers the aggregate ([REMEMBERED] of them, the most
 * recently handled).
 *
 * An aggregate's entries are compared by their ordinal ([EntrySource.ordinalOf]): their version, or, in a topic of
 * commands or receipts (–), whose entries commit nothing, their offset. Parking, copies and
 * retries go by it.
 *
 * Each handling of an entry, its retries included, is observed by [observer], in the trace the entry
 * carries ([me.ahoo.wow.spi.log.Transport.trace]): what the handler does goes on in that trace.
 *
 * History migrate-data imported from 9.x is handed over as imported ([SubscribedEntry.imported]): a log entry at or
 * before [importedThrough], or a record a peer published so. A saga or a reactor passes over an imported record of a
 * peer's topic as it passes over an entry outside its filter, since 9.x already acted on it; a projection takes it,
 * and so does every subscription of the log, a compensation's parked retry of an imported entry among them.
 *
 * A fatal error ([NodeFatals.isFatal]: out of memory, say) is no failure of the handler: it is neither retried nor
 * parked, since the JVM is not to be trusted after one. Its lane records it as its node's fatal error ([fatals]) and
 * stops, its entry incomplete, so the checkpoint stays below it; the node hands the partition off, and its new owner
 * handles the entry again from the checkpoint. The worker's other lanes go on until the hand-off drains them. A
 * [StackOverflowError] is not fatal: it fails the handling like any error, retried and then parked.
 */
internal class SubscriptionWorker(
    private val group: String,
    val subscription: Subscription,
    val partition: Int,
    private val source: EntrySource,
    private val ledger: FailureLedger,
    private val options: SubscriptionOptions,
    private val clock: Clock,
    private val lanes: CoroutineScope,
    private val signals: SignalSender,
    checkpoint: Long,
    parked: Map<AggregateRef, Long>,
    private val notifier: ParkedNotifier = ParkedNotifier.NONE,
    private val observer: Observer = Observer.NONE,
    /** How the handler's failures are classified. */
    private val classification: FailureClassification = RuntimeFailures.DEFAULT,
    /** The jitter of the backpressure backoff. */
    private val random: Random = Random.Default,
    /** The sequence up to which the partition of the log holds imported history; 0 for none, and for a topic. */
    private val importedThrough: Long = 0L,
    /** Where a lane records the fatal error that stops it: its node's. */
    private val fatals: NodeFatals = NodeFatals.NONE,
) {
    private val permits = Semaphore(options.maxInFlight)

    /** The parked entries' runs this worker has in hand, apart from its live entries' [permits] ([ParkedRetries]). */
    internal val retryPermits = Semaphore(options.maxInFlight)

    /** The waits whose next hop is this subscription: whether an entry's is, and its signal once done with. */
    private val hops = WaitHops(subscription, signals, clock)

    /** Whether the handler acts on what it reads, so passes over a peer's imported records: a saga's or a reactor's. */
    private val actsOnEntries = subscription.kind == FunctionKind.SAGA || subscription.kind == FunctionKind.REACT

    /** Whether the partition is paused on a systemic failure, and the gate of each attempt. */
    private val pause = PartitionPause(group, subscription.name, partition, options.systemic, clock, observer)

    /** How many handlings run at once while the downstream pushes back. */
    private val throttle = PartitionThrottle(
        group,
        subscription.name,
        partition,
        options.maxInFlight,
        options.backpressure,
        clock,
        observer,
    )

    /** The throttle of the partition now, null while it holds nothing back. */
    val throttled: ThrottledPartition? get() = throttle.state

    /** The pause of the partition now, null while it runs. */
    val paused: PausedPartition? get() = pause.state

    /** How the handler is retried before its aggregate is parked: its subscription's budget. */
    private val retry: RetryPolicy = options.retryOf(subscription)

    /** How one entry is handled: its handler attempted within the budget, behind the pause and the throttle. */
    private val handling = EntryHandling(
        group,
        subscription,
        partition,
        source,
        options,
        retry,
        classification,
        pause,
        throttle,
        observer,
        random,
        importedThrough,
    )

    /** How a lane runs its live entries together, null for a subscription that runs each alone. */
    private val runs = if (subscription.runsTogether) LaneRuns(subscription, source, handling, hops, Ops()) else null
    private val pending = ArrayDeque<Slot>()
    private val outstanding = ZeroAwaitingCounter()
    private val active = HashMap<AggregateRef, Lane>()

    /** What the worker writes to the ledger: a park, and a retry's repark or resolution. */
    private val writes = ParkWrites(
        subscription.id,
        partition,
        source,
        ledger,
        pause,
        options.readRetry,
        clock,
        classification,
        subscription.retry,
        retry,
        options.backpressure,
    )

    /** The aggregates parked here, and the retry or skip running on each. Guarded by the monitor. */
    private val parking = ParkStates(parked)

    /** Of a source that [EntrySource.repeats], the last ordinal handled of the aggregates handled most recently. */
    private val handledThrough: LinkedHashMap<AggregateRef, Long>? = if (source.repeats) {
        object : LinkedHashMap<AggregateRef, Long>(REMEMBERED, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, Long>?): Boolean =
                size > REMEMBERED
        }
    } else {
        null
    }

    /** Where the worker's watermark is stored, and up to where it was. */
    val saves = CheckpointSaves(subscription, partition, checkpoint) { watermark }

    /** Set by [abandon] under the monitor: the lanes are cancelled, so a retry would never run. */
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
        outstanding.set(pending.size)
        if (!subscription.takes(stream) || skippedOnAdmit(entry) || passesOver(entry)) {
            complete(slot)
            if (hops.waitsHere(entry)) {
                val parked = parkedAt(entry)
                lanes.launch { hops.signal(entry, Result.success(HandledEntry.NOTHING), parked) }
            }
        } else {
            enqueue(stream.ref, Item.Live(entry, slot), first = false)
        }
    }

    /** Whether [entry] is a record a peer published as imported, which a saga or a reactor passes over. */
    private fun passesOver(entry: SourcedEntry): Boolean = actsOnEntries && entry.published?.imported == true

    /** A parked aggregate's entries from its parked one on, and copies of what a retry handled, are skipped. */
    private fun skippedOnAdmit(entry: SourcedEntry): Boolean =
        parkedAt(entry) || parking.retriedCopy(entry.stream.ref, source.ordinalOf(entry))

    /** Called under the monitor: whether [entry]'s aggregate is parked at or before it. */
    private fun parkedAt(entry: SourcedEntry): Boolean = parking.holds(entry.stream.ref, source.ordinalOf(entry))

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
        outstanding.set(pending.size)
    }

    private suspend fun drain(ref: AggregateRef, lane: Lane) {
        while (true) {
            val item = synchronized(this) {
                lane.items.removeFirstOrNull() ?: run {
                    active.remove(ref)
                    null
                }
            } ?: return
            try {
                when (item) {
                    is Item.Live -> runs?.live(item, lane) ?: runLive(item)
                    is Item.Retry -> runRetry(ref, item)
                    is Item.Skip -> runSkip(ref, item)
                }
            } catch (error: VirtualMachineError) {
                // An operator's retry or skip fails with it, whatever it is, rather than wait on a lane that stops.
                (item as? Item.Action)?.result?.completeExceptionally(error)
                // The lane stops here, still active, so the aggregate's later entries queue behind it unhandled.
                fatals.record(Component.SUBSCRIPTION, "$group/${subscription.name}", error, partition)
                throw error
            }
        }
    }

    private suspend fun runLive(item: Item.Live) {
        val entry = item.entry
        val (skipped, parked) = synchronized(this) { skippedInLane(entry) to parkedAt(entry) }
        val handled = if (skipped) {
            Result.success(HandledEntry.NOTHING)
        } else {
            handling.handle(entry)
        }
        handled.exceptionOrNull()?.let { park(entry, it) }
        synchronized(this) {
            if (!skipped && handled.isSuccess) {
                remember(entry)
            }
            complete(item.slot)
        }
        hops.signal(entry, handled, skipped && parked)
    }

    /** The worker's side of [runs]: its monitor sections, its path of an entry alone, and its parking. */
    private inner class Ops : LaneOps<Item.Live, Lane> {
        override fun take(first: Item.Live, lane: Lane, max: Int): List<Taken<Item.Live>> {
            synchronized(this@SubscriptionWorker) {
                val items = arrayListOf(first)
                while (items.size < max && lane.items.firstOrNull() is Item.Live) {
                    items += lane.items.removeFirst() as Item.Live
                }
                // In version order, as skippedInLane expects.
                return items.map { Taken(it, it.entry, skippedInLane(it.entry), parkedAt(it.entry)) }
            }
        }

        override fun settle(done: List<Taken<Item.Live>>, handled: Boolean, lane: Lane, rest: List<Taken<Item.Live>>) {
            synchronized(this@SubscriptionWorker) {
                if (handled) {
                    done.filterNot { it.skipped }.forEach { remember(it.entry) }
                }
                done.forEach { complete(it.item.slot) }
                // Actions go first in a lane and live entries last, so the first live entry ends the actions.
                val behindActions = lane.items.indexOfFirst { it is Item.Live }.takeIf { it >= 0 } ?: lane.items.size
                lane.items.addAll(behindActions, rest.map { it.item })
            }
        }

        override suspend fun handleAlone(item: Item.Live) = runLive(item)

        override suspend fun park(entry: SourcedEntry, failure: Throwable) {
            this@SubscriptionWorker.park(entry, failure)
        }
    }

    /**
     * Called under the monitor. Within a lane, entries arrive in their ordinal order ([EntrySource.ordinalOf]), except
     * a copy a source that [EntrySource.repeats] delivers again: an ordinal at or below the last one handled of the
     * aggregate.
     */
    private fun skippedInLane(entry: SourcedEntry): Boolean {
        val ref = entry.stream.ref
        val ordinal = source.ordinalOf(entry)
        if (parking.holds(ref, ordinal)) {
            return true
        }
        val handled = handledThrough?.get(ref)
        if (handled != null && ordinal <= handled) {
            return true
        }
        return parking.retriedCopyInLane(ref, ordinal)
    }

    /** Called under the monitor: [entry] is handled, so a copy of it, or of an earlier one, is dropped. */
    private fun remember(entry: SourcedEntry) {
        handledThrough?.merge(entry.stream.ref, source.ordinalOf(entry), ::maxOf)
    }

    /**
     * Parks the aggregate at [sourced]'s version, and at its position in a topic; its later entries are skipped until
     * the ledger entry is resolved.
     */
    private suspend fun park(sourced: SourcedEntry, failure: Throwable) {
        val (entry, changed) = writes.park(sourced, failure)
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
        // As a restart reads it back from the ledger: an undecodable message parks from the first version.
        synchronized(this) { parking.park(entry.ref, source.ordinalOf(entry)) }
    }

    /**
     * Handles [ref]'s streams again from its parked version and resolves its ledger entry.
     *
     * The retry runs in the aggregate's lane, ahead of everything queued there, and the aggregate is marked
     * retrying in the same step, so from then on its new entries queue behind the retry instead of being skipped.
     * The retry reads every stream committed by the time it runs; an entry of one of those streams that the tail
     * delivers later is skipped as a copy, and every newer one is handled after the retry, in version order.
     *
     * @return true when the aggregate was handled up to its last stream and is no longer parked; false when it was
     * not parked, a skip of it is running, or it failed again and stays parked from where it was.
     */
    suspend fun retry(ref: AggregateRef): Boolean {
        val entry = ledger.parked(subscription.id, ref)
        val result = synchronized(this) {
            check(!abandoned) { "Subscription ${subscription.name} stopped in partition $partition." }
            parking.running(ref) ?: run {
                if (entry == null) {
                    parking.resolved(ref)
                    return false
                }
                if (entry.skipOnly) {
                    return false
                }
                val item = Item.Retry(entry, CompletableDeferred())
                parking.retrying(ref, item.result)
                enqueue(ref, item, first = true)
                item.result
            }
        }
        return result.await()
    }

    @Suppress("TooGenericExceptionCaught") // The failure goes to the caller of retry; the aggregate stays parked.
    private suspend fun runRetry(ref: AggregateRef, item: Item.Retry) {
        var parked = item.parked
        try {
            // A lower entry may have failed and been parked while this retry waited in the lane.
            ledger.parked(subscription.id, ref)?.takeIf { source.ordinalOf(it) < source.ordinalOf(parked) }?.let {
                parked = it
            }
            val entries = try {
                source.retryEntries(parked).toList()
            } catch (gone: UnrecoverableException) {
                writes.unrecoverable(parked, gone)
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
                val failure = if (subscription.takes(entry.stream) && !passesOver(entry)) {
                    handling.handle(entry).exceptionOrNull()
                } else {
                    null
                }
                if (failure != null) {
                    // Still failing: the entries before this one are handled, so the aggregate stays parked from
                    // this one, with the new reason; a resolve that raced it leaves nothing to update.
                    parked = writes.repark(entry, failure, parked.partition)
                    if (last != null) {
                        // Copies of the entries handled here that the tail delivers later are skipped.
                        synchronized(this) { parking.retried(ref, last) }
                    }
                    restoreParked(ref, item, parked)
                    item.result.complete(false)
                    return
                }
                last = ordinal
            }
            // In the partition read: an entry re-homed meanwhile stays parked, its entries there unhandled here.
            if (!writes.resolve(ref, parked.partition)) {
                restoreParked(ref, item, parked)
                item.result.complete(false)
                return
            }
            synchronized(this) {
                parking.resolved(ref)
                parking.retried(ref, last)
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

    private fun restoreParked(ref: AggregateRef, item: Item.Retry, parked: ParkedAggregate) = synchronized(this) {
        parking.parkedAgain(ref, item.result, source.ordinalOf(parked))
    }

    /**
     * Resolves [ref]'s ledger entry without handling what was skipped: its entries taken once it answers are handled.
     *
     * The aggregate is marked skipping in the same step as it is checked: a retry asked for meanwhile starts nothing,
     * so what the skip discards is never handled. The ledger is written in the aggregate's lane, ahead of the entries
     * queued there and after the one it runs now, so a failure of that entry parks before the skip resolves, and the
     * skip resolves that parking: the ledger and the worker agree whichever came first. When the write fails, the
     * aggregate is parked as it was.
     *
     * @return true when the aggregate was parked; false when it was not, or a retry or another skip of it is running.
     */
    suspend fun skip(ref: AggregateRef): Boolean {
        val result = synchronized(this) {
            check(!abandoned) { "Subscription ${subscription.name} stopped in partition $partition." }
            if (!parking.skipping(ref)) {
                return false
            }
            Item.Skip(CompletableDeferred()).also { enqueue(ref, it, first = true) }.result
        }
        return result.await()
    }

    @Suppress("TooGenericExceptionCaught") // The failure goes to the caller of skip; the aggregate stays parked.
    private suspend fun runSkip(ref: AggregateRef, item: Item.Skip) {
        var resolved = false
        val outcome = try {
            val removed = ledger.resolve(subscription.id, ref, partition)
            // In this partition: an entry re-homed to another stays parked, its skip that partition's owner's.
            resolved = removed != null || ledger.parked(subscription.id, ref) == null
            Result.success(removed != null)
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            // Settled before the skip answers: an entry taken once it has is handled, never held back as a parked one.
            synchronized(this) { parking.skipped(ref, resolved) }
        }
        outcome.fold({ item.result.complete(it) }, { item.result.completeExceptionally(it) })
        (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
    }

    /** Waits until every taken entry is complete. */
    suspend fun awaitDrained() {
        outstanding.awaitZero()
    }

    /** Fails the retries and skips still queued: their lanes were cancelled by a stop. */
    fun abandon() = synchronized(this) {
        abandoned = true
        active.values.flatMap { it.items }.filterIsInstance<Item.Action>().forEach {
            it.result.cancel(CancellationException("The subscription runtime stopped."))
        }
        active.clear()
    }

    private class Slot(val sequence: Long) {
        var done = false
    }

    private class Lane {
        val items = ArrayDeque<Item>()
    }

    private sealed interface Item {
        class Live(val entry: SourcedEntry, val slot: Slot) : Item

        /** An operator's action on the aggregate, answered with [result]. */
        sealed interface Action : Item {
            val result: CompletableDeferred<Boolean>
        }

        class Retry(val parked: ParkedAggregate, override val result: CompletableDeferred<Boolean>) : Action
        class Skip(override val result: CompletableDeferred<Boolean>) : Action
    }
}

private val logger = KotlinLogging.logger {}

/** How many aggregates a worker of a source that repeats remembers the last version handled of. */
private const val REMEMBERED = 4_096
private const val LOAD_FACTOR = 0.75f
