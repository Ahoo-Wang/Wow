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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.health.launchWorker
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.store.StoreCallMarks
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * How a [PartitionAppendQueue] groups appends: the most appends one partition's [EventLog.appendAll] takes
 * ([maxBatch]). The queue is always on, for every backend (measured 2026-10-23): a group is one
 * statement on PostgreSQL and one `insertMany` per collection on MongoDB, which carry more under load than single
 * appends and cost a few per cent at low concurrency. These are the backends' constants, not settings.
 *
 * A log that takes several partitions at once ([EventLog.appendsAcrossPartitions]) gets the partitions' groups merged:
 * [mergers] committers, each taking its share of the streams the partitions handed and no committer took yet (those
 * streams over [mergers], rounded up; the oldest group whatever its size), at most [mergeMax], into one call; while
 * no more than [shareFloor] streams wait, a committer takes them all. A log that does not take several partitions is
 * called per partition.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class AppendQueueOptions @InternalWowApi constructor(
    val maxBatch: Int = DEFAULT_MAX_BATCH,
    val mergers: Int = DEFAULT_MERGERS,
    val mergeMax: Int = DEFAULT_MERGE_MAX,
    /** The most streams waiting that a committer still takes all of, rather than its share. */
    @property:InternalWowApi
    val shareFloor: Int = DEFAULT_SHARE_FLOOR,
) {
    init {
        require(maxBatch >= 1) { "A batch takes at least one append." }
        require(mergers >= 1) { "Merging takes at least one committer." }
        require(mergeMax >= maxBatch) { "A merged call takes at least one partition's batch." }
        require(shareFloor >= 0) { "The share's floor is never negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AppendQueueOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AppendQueueOptions(maxBatch=$maxBatch, mergers=$mergers, mergeMax=$mergeMax, shareFloor=$shareFloor)"

    private fun valueFields(): List<Any?> = listOf(maxBatch, mergers, mergeMax, shareFloor)

    companion object {
        const val DEFAULT_MAX_BATCH: Int = 64

        /**
         * Two committers, measured (2026-10-24): one call in flight while the next merges what the partitions hand
         * meanwhile. With one, every group waited for the call in flight (E3's p50 1.6 times unmerged); with four, the
         * calls carried too little to save much.
         */
        const val DEFAULT_MERGERS: Int = 2

        /** The streams of eight partitions' full groups. */
        const val DEFAULT_MERGE_MAX: Int = 512

        /**
         * The streams waiting at or under which a committer takes them all, as one committer would: measured
         * (2026-11-13), a share of a small backlog at low load cost a one-core MongoDB a tenth at 16 clients, two
         * calls' fixed cost where one carried them; above it the share keeps the lanes out of step.
         */
        @InternalWowApi
        const val DEFAULT_SHARE_FLOOR: Int = 32
    }
}

/**
 * The partition owner's append queue: the [EventLog] the kernel
 * appends through on a node. Appends to one partition from any number of aggregates queue on the partition's lane,
 * and one coroutine per lane commits what is queued with one [EventLog.appendAll] (group commit). On MongoDB, whose
 * transactions abort each other when two writers bump one partition's counter, this turns contention into batching
 * (about 100 appends a second with 32 concurrent writers on one partition, about 5.6 thousand queued).
 *
 * Each caller gets its own stream's outcome, as [EventLog.appendAll] returns it: a conflict or a duplicate of one
 * stream does not fail the others. The streams of one aggregate that one call hands over (a kernel's batch)
 * queue as one unit and go into one batch together, so the log commits them as a prefix; a unit of an
 * aggregate already in the batch waits for the next one, so appends of one aggregate keep their order. A batch whose [EventLog.appendAll]
 * throws fails each of its callers with that exception, as a failing [EventLog.append] would; the lane goes on with
 * the next batch. A caller cancelled while its append is still queued takes it out of the queue: it is never
 * appended. Once taken into a batch, the append completes whatever the caller does.
 *
 * When the log takes several partitions a call ([EventLog.appendsAcrossPartitions]), the lanes hand
 * their batches to [AppendQueueOptions.mergers] committers instead of calling the log themselves: a free committer
 * takes the oldest batch handed and those after it, in turn, up to its share of the streams handed and not taken yet
 * (those streams over the committers, rounded up), at most [AppendQueueOptions.mergeMax], and commits them with one
 * call. The store's fixed cost of a call is then shared
 * by the partitions, whose batches are small at moderate load (one or two streams). A lane waits for its batch before
 * it takes the next, so each partition has one batch in flight, as without merging; a merged call that throws fails
 * every caller of its batches.
 *
 * **A share, not all** (measured 2026-11-13, with two committers): each lane has one batch handed or in flight, so a
 * call that took every batch handed held every lane; they then handed their next batches one after another as it
 * completed, and another committer started on the first of them alone: a small call after each large one, a full
 * call's fixed cost each, the lanes moving in step with the calls. A share leaves the other committers a backlog to
 * start from. A lone committer, with no one to leave the rest to, takes it all, and so does any committer while no
 * more than [AppendQueueOptions.shareFloor] streams wait: at low load two calls of a small backlog cost two calls'
 * fixed cost where one would carry it. A batch is never split, so a lone batch at low load is one call at once, as
 * before; the oldest batches go first, a lane's in turn, and what a call leaves stays at the head of the handed
 * batches for the next free committer.
 *
 * The queue only serializes the appends
 * of this node: a partition appended to from two nodes (Local fallback during a hand-off) still
 * contends in the database, and the backend's own retry keeps it correct. The kernel stays unaware of it.
 *
 * Each partition's lane holds at most [capacity] appends: the node's in-flight bound
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

    private val root = SupervisorJob()
    private val scope = CoroutineScope(context + root)
    private val lanes = List(log.partitions) { Channel<Pending>(capacity) }

    /** Whether the lanes' groups go to the log merged, several partitions a call. */
    val merging: Boolean get() = log.appendsAcrossPartitions

    private val handed = Channel<Handed>(Channel.UNLIMITED)

    /** The streams of the batches [handed] that no committer took yet. */
    private val handedStreams = AtomicInteger()

    // Before the lanes' workers, which hand their groups to it.
    private val mergers: List<Job> =
        if (merging) List(options.mergers) { scope.launchWorker("append merger") { merge() } } else emptyList()

    private val workers: List<Job> = lanes.mapIndexed { partition, lane ->
        val queued = ArrayDeque<Pending>()
        scope.launchWorker("append lane $partition") { drain(lane, queued) }
    }

    /**
     * A lane's group, handed to the mergers: done once committed. [counted] by the counts that saw it handed, when
     * there were any, so that only those count it taken.
     */
    private class Handed(val batch: List<Pending>, val counted: AppendQueueCounts?) {
        val done = CompletableDeferred<Unit>()
        val streams: Int = batch.sumOf { it.streams.size }
    }

    private val counting = AtomicReference<AppendQueueCounts?>()

    /**
     * What the queue's calls carried and the groups waiting for a committer ([AppendQueueCounts]), counted from the
     * first call of this on. Until then the queue counts nothing: its only cost is reading that no one asked.
     */
    fun counts(): AppendQueueCounts =
        counting.get() ?: AppendQueueCounts().let { fresh -> counting.compareAndExchange(null, fresh) ?: fresh }

    /**
     * Commits a group of the lanes' groups with one call: the oldest handed, and those after it in turn, up to this
     * committer's share of the streams handed and not taken (over the committers, rounded up; the oldest group whatever
     * its size), at most [AppendQueueOptions.mergeMax]; each lane then takes its next group. False once closed.
     */
    private suspend fun merge(): Boolean {
        val first = handed.receiveCatching().getOrNull() ?: return false
        val waiting = first.streams + handedStreams.addAndGet(-first.streams)
        val limit = if (waiting <= options.shareFloor) {
            options.mergeMax
        } else {
            minOf(options.mergeMax, (waiting + options.mergers - 1) / options.mergers)
        }
        val group = arrayListOf(first)
        var streams = first.streams
        while (streams < limit) {
            val next = handed.tryReceive().getOrNull() ?: break
            handedStreams.addAndGet(-next.streams)
            group += next
            streams += next.streams
        }
        group.forEach { it.counted?.taken(it.streams) }
        val call = counting.get()?.let { it.clock.start() }
        commit(group.flatMap { it.batch }, call?.marks)
        group.forEach { it.done.complete(Unit) }
        call?.done()
        return true
    }

    private val waiting = AtomicInteger()

    @Volatile
    private var closed = false

    /** The appends queued and not taken into a batch yet (the queue depth gauge). */
    val queued: Int get() = waiting.get()

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        return await(listOf(enqueue(listOf(Append.of(stream, transport))))).single().single()
    }

    /**
     * Queues the streams on their partitions' lanes, each aggregate's as one unit, grouped with what other callers
     * queued, and awaits them all.
     */
    override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
        val units = streams.indices.groupBy { streams[it].stream.ref }.values.toList()
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
    private suspend fun enqueue(streams: List<Append>): Pending {
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

    /** Commits one batch of [lane], [queued] holding what it read and did not take yet; false once the lane closed. */
    private suspend fun drain(lane: Channel<Pending>, queued: ArrayDeque<Pending>): Boolean {
        if (queued.isEmpty()) {
            queued.addLast(lane.receiveCatching().getOrNull() ?: return false)
        }
        while (queued.size < options.maxBatch) {
            queued.addLast(lane.tryReceive().getOrNull() ?: break)
        }
        val batch = take(queued)
        if (batch.isNotEmpty()) {
            if (merging) {
                // One group of the partition in flight: its next waits, so the partition's order holds.
                val handing = Handed(batch, counting.get())
                handing.counted?.handed(handing.streams)
                handedStreams.addAndGet(handing.streams)
                handed.send(handing)
                handing.done.await()
            } else {
                val call = counting.get()?.let { it.clock.start() }
                commit(batch, call?.marks)
                call?.done()
            }
        }
        return true
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

    /** Not cancellable: an append taken into a batch completes. [marks], when clocked, go in the log call's context. */
    private suspend fun commit(batch: List<Pending>, marks: StoreCallMarks?) = withContext(
        if (marks == null) NonCancellable else NonCancellable + marks,
    ) {
        val streams = batch.flatMap { it.streams }
        counting.get()?.called(streams.size, if (merging) options.mergeMax else options.maxBatch)
        val outcomes = try {
            log.appendAll(streams).also {
                marks?.returned()
                check(it.size == streams.size) { "The log returned ${it.size} outcomes for ${streams.size} appends." }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the log throws, each caller of the batch gets it.
            failure: Throwable,
        ) {
            marks?.returned()
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
        val streams: List<Append>,
        private val waiting: AtomicInteger,
    ) {
        val ref: AggregateRef = streams.first().stream.ref
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
