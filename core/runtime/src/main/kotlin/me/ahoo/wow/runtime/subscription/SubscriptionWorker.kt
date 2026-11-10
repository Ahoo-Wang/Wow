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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.HandlerTimeoutException
import me.ahoo.wow.kernel.HandlingRejectedException
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.handledResult
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.FailureClass
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
 * and one whose position the topic no longer retains marks the entry unrecoverable ([ParkedAggregate.UNRECOVERABLE]),
 * so it can only be skipped. A topic may deliver a record twice ([EntrySource.repeats]): the worker drops a copy of
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
    private val pending = ArrayDeque<Slot>()
    private val outstanding = ZeroAwaitingCounter()
    private val active = HashMap<AggregateRef, Lane>()

    /** What a retry writes to the ledger when it ends without handling all of an aggregate. */
    private val writes = RetryWrites(subscription.name, partition, source, ledger, pause, options.readRetry, clock)

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
    private val flushLock = Mutex()

    /** The last position stored. Written under `flushLock`. */
    @Volatile
    private var saved = checkpoint

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
            if (waitsHere(entry)) {
                val parked = parkedAt(entry)
                lanes.launch { signal(entry, Result.success(HandledEntry.NOTHING), parked) }
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

    /** Whether a wait on [entry] waits for this subscription next. */
    private fun waitsHere(entry: SourcedEntry): Boolean {
        val hop = entry.transport?.wait?.nextHandled ?: return false
        return hop.subscription == subscription.hopOf(entry.stream.ref.type) &&
            (hop.context ?: subscription.context) == subscription.context
    }

    /**
     * Signals the wait on [entry], if it waits for this subscription next and is not over: SUCCEEDED with the
     * commands [handled] derived, REJECTED with the rejection it met, or FAILED when it failed or the aggregate is
     * [parked]. A FAILED signal carries the code and where the aggregate parked, never the handler's exception: its
     * class and message, which may name a database, an address or a third party's answer, go to the ledger and the
     * logs only.
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
            failure != null || parked -> {
                // The handler's exception stays in the ledger and the logs; the caller learns only where.
                failure?.let { logger.debug(it) { "Wait ${wait.waitId} fails: ${subscription.name} parked." } }
                ErrorInfo(
                    ResultCodes.SUBSCRIPTION_PARKED,
                    "${entry.stream.ref}@${entry.stream.version} is parked by ${subscription.name}; the failure " +
                        "ledger has the cause.",
                )
            }
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
            signals.send(wait.origin, WaitSignal(waitId = wait.waitId, result = result), wait.deadline)
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
                    is Item.Live -> if (subscription.coalesces) runBatch(item, lane) else runLive(item)
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
            handle(entry)
        }
        handled.exceptionOrNull()?.let { park(entry, it) }
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
        result.exceptionOrNull()?.let { park(handled.first().item.entry, it) }
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
     * Runs the handler with its subscription's retries ([retry]); returns what it did, or the last failure once the
     * retries are spent. An attempt that outlasts the budget's timeout is cancelled and fails with a
     * [HandlerTimeoutException]. A [HandlingRejectedException] is not retried: a business outcome does not change.
     */
    private suspend fun handle(sourced: SourcedEntry): Result<HandledEntry> {
        val stream = sourced.stream
        val trace = sourced.transport?.trace.orEmpty()
        val observed = observer.handling(group, subscription.name, subscription.context, stream, trace, source.topic)
        return observed.observe {
            retried(sourced).also { handled -> handled.exceptionOrNull()?.let { observed.failed(it) } }
        }
    }

    /**
     * A handler may fail with anything: a [CancellationException] that is not this lane's own, such as
     * the handler's own `withTimeout` firing, or an [Error] such as [NotImplementedError] or [StackOverflowError], is a
     * failure like any other, retried and then parked, so the lane goes on and the checkpoint moves. Only the
     * cancellation of the lane itself stops the retries, and a fatal error ([NodeFatals.isFatal]: out of memory, say)
     * propagates at once, to stop the lane ([drain]).
     *
     * Each failure is classified ([classification]): an unrecoverable one returns at once, so
     * the aggregate parks without a retry; a recoverable one is retried within the budget; a systemic one, or any
     * failure while the partition is paused, is the partition's ([pause]): the entry is tried again once the pause lets
     * it, spending no attempt.
     *
     * A downstream that pushed back ([FailureClass.BACKPRESSURE]: a command refused as busy, a wait
     * that ran out, a busy query store, a 429 with `Retry-After`) is not a failure of the aggregate:
     * the entry is tried again after the backpressure backoff with jitter, at least the `Retry-After`, without
     * spending an attempt of the handler's budget, and the partition is throttled ([throttle]) until attempts succeed
     * again; once [BackpressureOptions.maxAttempts] are spent, the aggregate parks as after any spent budget. It never
     * pauses the partition, which would only slow the overloaded downstream's recovery further.
     */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    private suspend fun retried(sourced: SourcedEntry): Result<HandledEntry> {
        val ref = sourced.stream.ref
        var failures = 0
        var pushedBack = 0
        while (true) {
            val probing = pause.admit()
            throttle.acquire()
            val failure = try {
                val handled = attempt(sourced)
                succeeded(ref)
                return Result.success(handled)
            } catch (rejected: HandlingRejectedException) {
                // The handler reached a decision: what it depends on answers.
                succeeded(ref)
                return Result.failure(rejected)
            } catch (failure: Throwable) {
                if (NodeFatals.isFatal(failure)) {
                    // Never retried: the lane stops ([drain]).
                    throw failure
                }
                // The lane's own cancellation propagates; any other is the handler's failure.
                currentCoroutineContext().ensureActive()
                failure
            } finally {
                throttle.release()
                pause.release(probing)
            }
            val kind = classification.classify(failure, subscription.retry)
            if (pause.failed(ref, kind, failure, probing)) {
                continue
            }
            if (kind == FailureClass.BACKPRESSURE) {
                // Never the partition's pause: this entry backs off, the partition slows down.
                pushedBack++
                throttle.backpressure(ref)
                observer.observed(ObservedEvent.Backpressured(group, subscription.name, partition, codeOf(failure)))
                if (pushedBack >= options.backpressure.maxAttempts) {
                    return Result.failure(failure)
                }
                val retryAfter = FailureClassification.retryAfterOf(failure)
                logger.debug { "Subscription ${subscription.name} met backpressure at $ref: ${failure.message}" }
                delay(options.backpressure.backoff(pushedBack, retryAfter, random.nextDouble()))
                continue
            }
            failures++
            if (kind == FailureClass.UNRECOVERABLE || failures >= retry.maxAttempts) {
                return Result.failure(failure)
            }
            delay(retry.backoff(failures))
        }
    }

    private fun succeeded(ref: AggregateRef) {
        pause.succeeded(ref)
        throttle.succeeded()
    }

    /** The code a backpressure [failure] names: the downstream's error code, its own or a cause's, else its class's full name. */
    private fun codeOf(failure: Throwable): String =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).firstNotNullOfOrNull {
            when (it) {
                is CommandBusyException -> ResultCodes.BUSY
                is WowProblemException -> it.code
                else -> null
            }
        } ?: failure.javaClass.name

    /** One attempt of the handler, within the budget's timeout when it has one. */
    private suspend fun attempt(sourced: SourcedEntry): HandledEntry {
        if (retry.timeout.isInfinite()) {
            return decodeAndHandle(sourced)
        }
        return withTimeoutOrNull(retry.timeout) { decodeAndHandle(sourced) }
            ?: throw HandlerTimeoutException(subscription.name, sourced.stream.ref, sourced.stream.version, retry.timeout)
    }

    private suspend fun decodeAndHandle(sourced: SourcedEntry): HandledEntry {
        (sourced.message as? UndecodableMessage)?.let { throw UndecodableEntryException(it.reason) }
        val decoded = subscription.decoder.decode(sourced.stream, sourced.position, sourced.transport)
        val imported = sourced.published?.imported ?: (sourced.position.sequence <= importedThrough)
        val entry = if (sourced.published == null && sourced.message == null && !imported) {
            decoded
        } else {
            decoded.copy(published = sourced.published, message = sourced.message, imported = imported)
        }
        return subscription.handle(entry)
    }

    /**
     * Parks the aggregate at [sourced]'s version, and at its position in a topic; its later entries are skipped until
     * the ledger entry is resolved.
     */
    private suspend fun park(sourced: SourcedEntry, failure: Throwable) {
        val stream = sourced.stream
        val kind = if (failure is HandlingRejectedException) {
            null
        } else {
            classification.classify(
                failure,
                subscription.retry
            )
        }
        val unrecoverable = kind == FailureClass.UNRECOVERABLE
        val entry = ParkedAggregate(
            subscription.name,
            stream.ref,
            partition,
            stream.version,
            reasonOf(failure),
            clock.millis(),
            source.sourcePosition(sourced),
            // As 9.x records its RecoverableType: an unrecoverable failure is not retried automatically either.
            recoverable = if (unrecoverable) Recoverable.UNRECOVERABLE else Recoverable.UNKNOWN,
        )
        logger.warn(failure) {
            val after = when {
                failure is HandlingRejectedException -> "a rejection"
                unrecoverable -> "an unrecoverable failure, which it does not retry automatically"
                kind == FailureClass.BACKPRESSURE ->
                    "${options.backpressure.maxAttempts} attempts its downstream pushed back"
                else -> "${retry.maxAttempts} attempts"
            }
            "Subscription ${subscription.name} parks ${stream.ref} from version ${stream.version} after $after."
        }
        // The ledger may sit on what paused the partition: it is written once the partition runs again.
        pause.awaitRunning()
        val changed = retryForever(options.readRetry, { "park ${stream.ref} for ${subscription.name}" }) {
            ledger.park(entry)
        }
        if (changed && notifier !== ParkedNotifier.NONE) {
            val notice = ParkedNotice(
                ParkedNotice.Kind.PARKED,
                group,
                subscription.context,
                entry,
                options.parked.specOf(entry, subscription.context),
                entry.failedAt,
            )
            lanes.launch { notifier.notifyQuietly(notice) }
        }
        // As a restart reads it back from the ledger: an undecodable message parks from the first version.
        synchronized(this) { parking.park(stream.ref, source.ordinalOf(entry)) }
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
        val entry = ledger.parked(subscription.name, ref)
        val result = synchronized(this) {
            check(!abandoned) { "Subscription ${subscription.name} stopped in partition $partition." }
            parking.running(ref) ?: run {
                if (entry == null) {
                    parking.resolved(ref)
                    return false
                }
                if (entry.unrecoverable) {
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
            ledger.parked(subscription.name, ref)?.takeIf { source.ordinalOf(it) < source.ordinalOf(parked) }?.let {
                parked = it
            }
            val entries = try {
                source.retryEntries(ref, parked.fromVersion, parked.fromPosition).toList()
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
                    handle(entry).exceptionOrNull()
                } else {
                    null
                }
                if (failure != null) {
                    // Still failing: the entries before this one are handled, so the aggregate stays parked from
                    // this one, with the new reason; a resolve that raced it leaves nothing to update.
                    parked = writes.repark(entry, failure)
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
            ledger.resolve(subscription.name, ref)
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
     * Resolves [ref]'s ledger entry without handling what was skipped: from now on its new entries are handled.
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
        try {
            val removed = ledger.resolve(subscription.name, ref)
            resolved = true
            item.result.complete(removed != null)
        } catch (cancelled: CancellationException) {
            item.result.cancel(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            item.result.completeExceptionally(failure)
        } finally {
            synchronized(this) { parking.skipped(ref, resolved) }
        }
    }

    /** Saves the watermark if it moved since the last save. */
    suspend fun flush() = flushLock.withLock {
        val position = watermark
        if (position > saved) {
            checkpoints.save(group, subscription.name, partition, position)
            saved = position
        }
    }

    /**
     * The watermark when it moved since the last save, else null: what the runtime's periodic save stores, for all
     * its workers at once ([CheckpointStore.saveAll]), then reports with [saved].
     */
    fun unsaved(): Checkpoint? {
        val position = watermark
        return if (position > saved) Checkpoint.of(subscription.name, partition, position) else null
    }

    /** Records that [checkpoint], from [unsaved], is stored. */
    suspend fun saved(checkpoint: Checkpoint) = flushLock.withLock {
        if (checkpoint.position > saved) {
            saved = checkpoint.position
        }
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

/** The causes [SubscriptionWorker.codeOf] looks through at most. */
private const val MAX_CAUSES = 16
