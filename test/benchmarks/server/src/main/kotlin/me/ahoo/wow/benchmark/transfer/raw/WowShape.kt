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

package me.ahoo.wow.benchmark.transfer.raw

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wow's own write path at its peak, the shape the node gives the store, with the driver alone: a caller's command
 * queues on its partition's lane (PartitionAppendQueue); [Options.appenders] committers each take what the lanes hand
 * them, a lane's batch of at most [Options.maxBatch] streams from each lane with none in flight, up to
 * [Options.mergeMax] streams, and append them with one call ([append]); once a command's stream is in the log, its
 * snapshot queues for [Options.writers] writers, each saving up to [Options.writerMax] rows a call ([save]), as the
 * node's snapshot writes do. Each appender and writer has its own store connection ([open]).
 *
 * A caller ([submit]) waits for its command: it counts once its stream and its snapshot were both written, as the
 * other cases count a command; a store call that throws fails each command it carried.
 */
class WowShape<S : AutoCloseable>(
    private val partitions: Int,
    private val partitionOf: (Aggregates.Command) -> Int,
    private val options: Options,
    open: () -> S,
    private val append: (S, List<Aggregates.Command>) -> BooleanArray,
    private val save: (S, List<Aggregates.Command>) -> BooleanArray,
) : AutoCloseable {
    /**
     * The shape: Wow's defaults (AppendQueueOptions: two mergers, a lane's batch of 64, calls of up to 512 streams;
     * two snapshot writers of up to 256 rows), [mergeMax] the one the cases vary.
     */
    data class Options(
        val mergeMax: Int = MERGE_MAX,
        val appenders: Int = APPENDERS,
        val maxBatch: Int = MAX_BATCH,
        val writers: Int = WRITERS,
        val writerMax: Int = WRITER_MAX,
    ) {
        /** The store connections the shape holds: one an appender, one a writer. */
        val connections: Int get() = appenders + writers
    }

    private class Pending(val command: Aggregates.Command) {
        val done = CompletableFuture<Boolean>()
    }

    private val lock = ReentrantLock()
    private val handed = lock.newCondition()
    private val lanes = Array(partitions) { ArrayDeque<Pending>() }
    private val inFlight = BooleanArray(partitions)
    private var cursor = 0

    @Volatile
    private var closing = false

    @Volatile
    private var appended = false
    private val saves = LinkedBlockingQueue<Pending>()
    private val stores = ArrayList<S>()
    private val appenders = ArrayList<Thread>()
    private val writers = ArrayList<Thread>()

    init {
        try {
            repeat(options.appenders) { index ->
                val store = open().also { stores += it }
                appenders += Thread.ofPlatform().name("raw-appender-$index").start { appendLoop(store) }
            }
            repeat(options.writers) { index ->
                val store = open().also { stores += it }
                writers += Thread.ofPlatform().name("raw-writer-$index").start { saveLoop(store) }
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** Queues [command] and waits for it: whether its stream and its snapshot were both written. */
    fun submit(command: Aggregates.Command): Boolean {
        val pending = Pending(command)
        lock.withLock {
            check(!closing) { "The shape is closed." }
            lanes[partitionOf(command)].addLast(pending)
            handed.signal()
        }
        return try {
            pending.done.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun appendLoop(store: S) {
        while (true) {
            val (taken, parts) = take() ?: return
            val written = try {
                append(store, taken.map { it.command })
            } catch (failure: Exception) {
                taken.forEach { it.done.completeExceptionally(failure) }
                null
            }
            lock.withLock {
                parts.forEach { inFlight[it] = false }
                handed.signalAll()
            }
            if (written != null) {
                taken.forEachIndexed { at, pending ->
                    if (written[at]) {
                        saves.put(pending)
                    } else {
                        pending.done.complete(false)
                    }
                }
            }
        }
    }

    /**
     * The next call's streams: from each lane with none in flight, in turn from where the last call began, a batch of
     * up to the lane's maximum, until the call's maximum; and the lanes it took from. Waits for one; null once closed.
     */
    private fun take(): Pair<List<Pending>, List<Int>>? = lock.withLock {
        while (!closing) {
            val taken = ArrayList<Pending>()
            val parts = ArrayList<Int>()
            for (k in 0 until partitions) {
                val part = (cursor + k) % partitions
                val room = minOf(options.maxBatch, options.mergeMax - taken.size)
                if (room <= 0) break
                if (inFlight[part] || lanes[part].isEmpty()) continue
                repeat(minOf(room, lanes[part].size)) { taken += lanes[part].removeFirst() }
                inFlight[part] = true
                parts += part
            }
            if (taken.isNotEmpty()) {
                cursor = (cursor + 1) % partitions
                return taken to parts
            }
            handed.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        null
    }

    private fun saveLoop(store: S) {
        while (!appended || saves.isNotEmpty()) {
            val first = saves.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            val batch = arrayListOf(first)
            saves.drainTo(batch, options.writerMax - 1)
            try {
                val written = save(store, batch.map { it.command })
                batch.forEachIndexed { at, pending -> pending.done.complete(written[at]) }
            } catch (failure: Exception) {
                batch.forEach { it.done.completeExceptionally(failure) }
            }
        }
    }

    /**
     * Stops: the appenders finish their calls, then the writers save what those appended; a command still queued
     * (none, once every caller has its answer) fails. Then the store connections close.
     */
    override fun close() {
        lock.withLock {
            closing = true
            handed.signalAll()
        }
        appenders.forEach { it.join() }
        appended = true
        writers.forEach { it.join() }
        val left = IllegalStateException("The shape closed before the command was written.")
        lock.withLock { lanes.forEach { lane -> lane.forEach { it.done.completeExceptionally(left) } } }
        stores.forEach { runCatching { it.close() } }
    }

    companion object {
        /** Wow's defaults (AppendQueueOptions, the snapshot writes). */
        const val MERGE_MAX = 512
        const val APPENDERS = 2
        const val MAX_BATCH = 64
        const val WRITERS = 2
        const val WRITER_MAX = 256
        private const val POLL_MILLIS = 50L

        /** The calls' maxima the cases price: one lane's batch, four lanes', and Wow's default of eight. */
        val MERGES = listOf(MAX_BATCH, 256, MERGE_MAX)
    }
}
