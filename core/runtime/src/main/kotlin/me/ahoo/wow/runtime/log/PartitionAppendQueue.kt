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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Whether a [PartitionAppendQueue] queues appends ([enabled]), and the most appends one [EventLog.appendAll] takes
 * ([maxBatch]). A node's log backend picks its default: [MONGO] or [POSTGRES].
 */
data class AppendQueueOptions(val enabled: Boolean = true, val maxBatch: Int = DEFAULT_MAX_BATCH) {
    init {
        require(maxBatch >= 1) { "A batch takes at least one append." }
    }

    companion object {
        private const val DEFAULT_MAX_BATCH = 64

        /**
         * MongoDB: on. Its transactions abort each other when two writers bump one partition's counter, so the
         * partition owner serializes the appends and commits them in groups (decisions D11, D52; spike H1).
         */
        val MONGO = AppendQueueOptions(enabled = true)

        /**
         * PostgreSQL: off, appends go straight to the log. One autocommit statement allocates the sequence (D10);
         * contending writers queue on the counter row instead of aborting, and the log commits a group one stream at
         * a time, so the queue would only serialize. Not measured to be faster; turn it on where it is.
         */
        val POSTGRES = AppendQueueOptions(enabled = false)
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
 * stream does not fail the others. A batch holds distinct aggregates; a second append of an aggregate already in the
 * batch waits for the next one, so appends of one aggregate keep their order. A batch whose [EventLog.appendAll]
 * throws fails each of its callers with that exception, as a failing [EventLog.append] would; the lane goes on with
 * the next batch. A caller cancelled while its append is still queued takes it out of the queue: it is never
 * appended. Once taken into a batch, the append completes whatever the caller does.
 *
 * Disabled by [options] ([enabled] false), every call goes straight to [log]. The queue only serializes the appends
 * of this node: a partition appended to from two nodes (Local fallback during a hand-off, decision D71) still
 * contends in the database, and the backend's own retry keeps it correct. The kernel stays unaware of it (D43).
 *
 * [close] stops taking appends and returns once the queued ones are committed.
 */
class PartitionAppendQueue(
    private val log: EventLog,
    private val options: AppendQueueOptions = AppendQueueOptions(),
    context: CoroutineContext = Dispatchers.Default,
) : EventLog by log {
    /** Whether appends queue per partition; when not, they go straight to the log. */
    val enabled: Boolean get() = options.enabled

    private val root = SupervisorJob()
    private val scope = CoroutineScope(context + root)
    private val lanes = if (enabled) List(log.partitions) { Channel<Pending>(Channel.UNLIMITED) } else emptyList()
    private val workers: List<Job> = lanes.map { lane -> scope.launch { drain(lane) } }

    private val waiting = AtomicInteger()

    @Volatile
    private var closed = false

    /** The appends queued and not taken into a batch yet. */
    internal val queued: Int get() = waiting.get()

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        if (!enabled) {
            return log.append(stream, transport)
        }
        return await(listOf(enqueue(stream, transport))).single()
    }

    /** Queues each stream on its partition's lane, grouped with what other callers queued, and awaits them all. */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        if (!enabled) {
            return log.appendAll(streams)
        }
        val queued = ArrayList<Pending>(streams.size)
        try {
            streams.forEach { (stream, transport) -> queued += enqueue(stream, transport) }
        } catch (closed: IllegalStateException) {
            queued.forEach { it.withdraw() }
            throw closed
        }
        return await(queued)
    }

    private fun enqueue(stream: EventStreamRecord, transport: Transport?): Pending {
        val pending = Pending(stream, transport, waiting)
        val lane = lanes[Partitioner.partitionOf(stream.ref, log.partitions)]
        check(!closed) { "The append queue is closed." }
        waiting.incrementAndGet()
        if (!lane.trySend(pending).isSuccess) {
            waiting.decrementAndGet()
            throw IllegalStateException("The append queue is closed.")
        }
        return pending
    }

    private suspend fun await(queued: List<Pending>): List<AppendOutcome> = try {
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
                commit(batch)
            }
        }
    }

    /** Takes up to [AppendQueueOptions.maxBatch] appends of distinct aggregates, in queue order. */
    private fun take(queued: ArrayDeque<Pending>): List<Pending> {
        val batch = ArrayList<Pending>(queued.size)
        val refs = HashSet<AggregateRef>()
        val iterator = queued.iterator()
        while (iterator.hasNext() && batch.size < options.maxBatch) {
            val pending = iterator.next()
            if (pending.stream.ref in refs) {
                continue
            }
            iterator.remove()
            if (pending.take()) {
                batch += pending
                refs += pending.stream.ref
            }
        }
        return batch
    }

    /** Not cancellable: an append taken into a batch completes. */
    private suspend fun commit(batch: List<Pending>) = withContext(NonCancellable) {
        val outcomes = try {
            log.appendAll(batch.map { it.stream to it.transport }).also {
                check(it.size == batch.size) { "The log returned ${it.size} outcomes for ${batch.size} appends." }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the log throws, each caller of the batch gets it.
            failure: Throwable,
        ) {
            logger.debug(failure) { "A batch of ${batch.size} appends failed." }
            batch.forEach { it.outcome.completeExceptionally(failure) }
            return@withContext
        }
        batch.zip(outcomes).forEach { (pending, outcome) -> pending.outcome.complete(outcome) }
    }

    /** One queued append: taken into a batch by the lane, or withdrawn by its cancelled caller, never both. */
    private class Pending(
        val stream: EventStreamRecord,
        val transport: Transport?,
        private val waiting: AtomicInteger
    ) {
        val outcome = CompletableDeferred<AppendOutcome>()
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
