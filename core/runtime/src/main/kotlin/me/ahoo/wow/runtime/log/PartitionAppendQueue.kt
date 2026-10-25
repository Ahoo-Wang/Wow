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

package me.ahoo.wow.runtime.log

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Whether a [PartitionAppendQueue] queues appends ([enabled]), and the most appends one partition's
 * [EventLog.appendAll] takes ([maxBatch]). On by default for every backend (decision D248, measured 2026-10-23): a
 * group is one statement on PostgreSQL and one `insertMany` per collection on MongoDB, which carry more under load
 * than single appends and cost a few per cent at low concurrency. `wow.log.append-queue.enabled=false` turns it off.
 *
 * Whether the partitions' groups go to a log that takes several partitions at once ([EventLog.appendsAcrossPartitions])
 * merged ([merge], decision D275): [mergers] committers, each taking what the partitions handed it meanwhile, at most
 * [mergeMax] streams, into one call. On by default; a log that does not take them is called per partition.
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class AppendQueueOptions @InternalWowApi constructor(
    val enabled: Boolean = true,
    val maxBatch: Int = DEFAULT_MAX_BATCH,
    val merge: Boolean = true,
    val mergers: Int = DEFAULT_MERGERS,
    val mergeMax: Int = DEFAULT_MERGE_MAX,
) {
    init {
        require(maxBatch >= 1) { "A batch takes at least one append." }
        require(mergers >= 1) { "Merging takes at least one committer." }
        require(mergeMax >= maxBatch) { "A merged call takes at least one partition's batch." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AppendQueueOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AppendQueueOptions(enabled=$enabled, maxBatch=$maxBatch, merge=$merge, mergers=$mergers, mergeMax=$mergeMax)"

    private fun valueFields(): List<Any?> = listOf(enabled, maxBatch, merge, mergers, mergeMax)

    companion object {
        const val DEFAULT_MAX_BATCH: Int = 64

        /** One committer: a call in flight, the partitions' next groups merging behind it. */
        const val DEFAULT_MERGERS: Int = 1

        /** The streams of eight partitions' full groups. */
        const val DEFAULT_MERGE_MAX: Int = 512
    }
}

/**
 * The partition owner's append queue (design 03 §2; finding F1; decisions D11, D52): the [EventLog] the kernel
 * appends through on a node. Appends to one partition from any number of aggregates queue on the partition's lane,
 * and one coroutine per lane commits what is queued with one [EventLog.appendAll] (group commit). On MongoDB, whose
 * transactions abort each other when two writers bump one partition's counter, this turns contention into batching
 * (spike H1: about 100 appends a second with 32 concurrent writers on one partition, about 5.6 thousand queued).
 *
 * Each caller gets its own stream's outcome, as [EventLog.appendAll] returns it: a conflict or a duplicate of one
 * stream does not fail the others. The streams of one aggregate that one call hands over (a kernel's batch, roadmap §6
 * item 10) queue as one unit and go into one batch together, so the log commits them as a prefix; a unit of an
 * aggregate already in the batch waits for the next one, so appends of one aggregate keep their order. A batch whose [EventLog.appendAll]
 * throws fails each of its callers with that exception, as a failing [EventLog.append] would; the lane goes on with
 * the next batch. A caller cancelled while its append is still queued takes it out of the queue: it is never
 * appended. Once taken into a batch, the append completes whatever the caller does.
 *
 * When the log takes several partitions a call ([EventLog.appendsAcrossPartitions], decision D275), the lanes hand
 * their batches to [AppendQueueOptions.mergers] committers instead of calling the log themselves: a committer takes the
 * first batch handed and those handed meanwhile, at most [AppendQueueOptions.mergeMax] streams, and commits them with
 * one call. The store's fixed cost of a call is then shared by the partitions, whose batches are small at moderate load
 * (one or two streams). A lane waits for its batch before it takes the next, so each partition has one batch in flight,
 * as without merging; a merged call that throws fails every caller of its batches.
 *
 * Disabled by [options] ([enabled] false), every call goes straight to [log]. The queue only serializes the appends
 * of this node: a partition appended to from two nodes (Local fallback during a hand-off, decision D71) still
 * contends in the database, and the backend's own retry keeps it correct. The kernel stays unaware of it (D43).
 *
 * Each partition's lane holds at most [capacity] appends (decision D228 ②): the node's in-flight bound
 * ([me.ahoo.wow.runtime.InFlightLimit]), which every append's command is counted in, so the lane never fills on its
 * own; were it full, the caller would wait for room rather than queue without bound.
 *
 * [close] stops taking appends and returns once the queued ones are committed.
 */
@InternalWowApi
class PartitionAppendQueue(
    private val log: EventLog,
    private val options: AppendQueueOptions = AppendQueueOptions(),
    context: CoroutineContext = Dispatchers.Default,
    /** The most appends one partition's lane holds; the node's in-flight bound. */
    capacity: Int = InFlightLimit.DEFAULT_MAX,
) : EventLog by log {
    init {
        require(capacity >= 1) { "A lane holds at least one append." }
    }

    /** Whether appends queue per partition; when not, they go straight to the log. */
    val enabled: Boolean get() = options.enabled

    private val root = SupervisorJob()
    private val scope = CoroutineScope(context + root)
    private val lanes = if (enabled) List(log.partitions) { Channel<Pending>(capacity) } else emptyList()

    /** Whether the lanes' groups go to the log merged, several partitions a call (decision D275). */
    val merging: Boolean get() = enabled && options.merge && log.appendsAcrossPartitions

    private val handed = Channel<Handed>(Channel.UNLIMITED)

    // Before the lanes' workers, which hand their groups to it.
    private val mergers: List<Job> = if (merging) List(options.mergers) { scope.launch { merge() } } else emptyList()

    private val workers: List<Job> = lanes.map { lane -> scope.launch { drain(lane) } }

    /** A lane's group, handed to the mergers: done once committed. */
    private class Handed(val batch: List<Pending>) {
        val done = CompletableDeferred<Unit>()
    }

    /**
     * Commits the lanes' groups: the first handed, and those handed meanwhile, up to [AppendQueueOptions.mergeMax]
     * streams, with one call; each lane then takes its next group.
     */
    private suspend fun merge() {
        while (true) {
            val first = handed.receiveCatching().getOrNull() ?: return
            val group = arrayListOf(first)
            var streams = first.batch.sumOf { it.streams.size }
            while (streams < options.mergeMax) {
                val next = handed.tryReceive().getOrNull() ?: break
                group += next
                streams += next.batch.sumOf { it.streams.size }
            }
            commit(group.flatMap { it.batch })
            group.forEach { it.done.complete(Unit) }
        }
    }

    private val waiting = AtomicInteger()

    @Volatile
    private var closed = false

    /** The appends queued and not taken into a batch yet (review A5-11: the queue depth gauge). */
    val queued: Int get() = waiting.get()

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        if (!enabled) {
            return log.append(stream, transport)
        }
        return await(listOf(enqueue(listOf(stream to transport)))).single().single()
    }

    /**
     * Queues the streams on their partitions' lanes, each aggregate's as one unit, grouped with what other callers
     * queued, and awaits them all.
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        if (!enabled) {
            return log.appendAll(streams)
        }
        val units = streams.indices.groupBy { streams[it].first.ref }.values.toList()
        val queued = ArrayList<Pending>(units.size)
        try {
            units.forEach { unit -> queued += enqueue(unit.map { streams[it] }) }
        } catch (failure: IllegalStateException) {
            queued.forEach { it.withdraw() }
            throw failure
        } catch (cancelled: CancellationException) {
            queued.forEach { it.withdraw() }
            throw cancelled
        }
        val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
        units.zip(await(queued)).forEach { (unit, unitOutcomes) ->
            unit.zip(unitOutcomes).forEach { (index, outcome) -> outcomes[index] = outcome }
        }
        return outcomes.map { checkNotNull(it) }
    }

    /** Queues [streams], one aggregate's, as one unit on its partition's lane, waiting for room when it is full. */
    private suspend fun enqueue(streams: List<Pair<EventStreamRecord, Transport?>>): Pending {
        val pending = Pending(streams, waiting)
        val lane = lanes[Partitioner.partitionOf(pending.ref, log.partitions)]
        check(!closed) { "The append queue is closed." }
        waiting.incrementAndGet()
        try {
            lane.send(pending)
        } catch (closedLane: ClosedSendChannelException) {
            waiting.decrementAndGet()
            throw IllegalStateException("The append queue is closed.", closedLane)
        } catch (cancelled: CancellationException) {
            // A cancelled send may still have queued it (prompt cancellation): the lane skips it once withdrawn.
            pending.withdraw()
            throw cancelled
        }
        return pending
    }

    private suspend fun await(queued: List<Pending>): List<List<AppendOutcome>> = try {
        queued.map { it.outcome.await() }
    } catch (cancelled: CancellationException) {
        queued.forEach { it.withdraw() }
        throw cancelled
    }

    /** Stops taking appends, and returns once the queued ones are committed. */
    suspend fun close() {
        closed = true
        lanes.forEach { it.close() }
        workers.joinAll()
        handed.close()
        mergers.joinAll()
        root.cancel()
    }

    private suspend fun drain(lane: Channel<Pending>) {
        val queued = ArrayDeque<Pending>()
        while (true) {
            if (queued.isEmpty()) {
                queued.addLast(lane.receiveCatching().getOrNull() ?: return)
            }
            while (queued.size < options.maxBatch) {
                queued.addLast(lane.tryReceive().getOrNull() ?: break)
            }
            val batch = take(queued)
            if (batch.isNotEmpty()) {
                if (merging) {
                    // One group of the partition in flight: its next waits, so the partition's order holds.
                    val handing = Handed(batch)
                    handed.send(handing)
                    handing.done.await()
                } else {
                    commit(batch)
                }
            }
        }
    }

    /**
     * Takes units of distinct aggregates, in queue order, up to [AppendQueueOptions.maxBatch] streams; the first unit
     * whatever its size.
     */
    private fun take(queued: ArrayDeque<Pending>): List<Pending> {
        val batch = ArrayList<Pending>(queued.size)
        val refs = HashSet<AggregateRef>()
        var streams = 0
        val iterator = queued.iterator()
        while (iterator.hasNext() && streams < options.maxBatch) {
            val pending = iterator.next()
            if (pending.ref in refs || batch.isNotEmpty() && streams + pending.streams.size > options.maxBatch) {
                continue
            }
            iterator.remove()
            if (pending.take()) {
                batch += pending
                refs += pending.ref
                streams += pending.streams.size
            }
        }
        return batch
    }

    /** Not cancellable: an append taken into a batch completes. */
    private suspend fun commit(batch: List<Pending>) = withContext(NonCancellable) {
        val streams = batch.flatMap { it.streams }
        val outcomes = try {
            log.appendAll(streams).also {
                check(it.size == streams.size) { "The log returned ${it.size} outcomes for ${streams.size} appends." }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the log throws, each caller of the batch gets it.
            failure: Throwable,
        ) {
            logger.debug(failure) { "A batch of ${batch.size} appends failed." }
            batch.forEach { it.outcome.completeExceptionally(failure) }
            return@withContext
        }
        var at = 0
        batch.forEach { pending ->
            pending.outcome.complete(outcomes.subList(at, at + pending.streams.size))
            at += pending.streams.size
        }
    }

    /**
     * One queued unit, the streams of one aggregate in version order: taken into a batch by the lane, or withdrawn by
     * its cancelled caller, never both.
     */
    private class Pending(
        val streams: List<Pair<EventStreamRecord, Transport?>>,
        private val waiting: AtomicInteger,
    ) {
        val ref: AggregateRef = streams.first().first.ref
        val outcome = CompletableDeferred<List<AppendOutcome>>()
        private val state = AtomicInteger(QUEUED)

        fun take(): Boolean = leave(TAKEN)

        fun withdraw(): Boolean = leave(WITHDRAWN)

        private fun leave(to: Int): Boolean = state.compareAndSet(QUEUED, to).also {
            if (it) {
                waiting.decrementAndGet()
            }
        }
    }

    private companion object {
        const val QUEUED = 0
        const val TAKEN = 1
        const val WITHDRAWN = 2
        val logger = KotlinLogging.logger {}
    }
}
