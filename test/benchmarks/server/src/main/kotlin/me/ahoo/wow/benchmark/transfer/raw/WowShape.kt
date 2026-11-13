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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wow's own write path at its peak, the shape the node gives the store, with the driver alone:
 *
 * - a caller's command queues on its partition's lane (PartitionAppendQueue); a lane with no batch handed or in
 *   flight hands one at once, what it holds up to [Options.maxBatch] streams, fixed when handed;
 * - [Options.appenders] committers each take the first batch handed and those handed meanwhile, whole, while the call
 *   holds fewer than [Options.mergeMax] streams (so a call carries up to `mergeMax + maxBatch - 1`), and append them
 *   with one call ([append]); a lane hands its next batch once its batch is written;
 * - once a command's stream is in the log, its snapshot queues for [Options.writers] writers, each saving up to
 *   [Options.writerMax] rows a call ([save]), as the node's snapshot writes do (SnapshotWrites).
 *
 * Each appender and writer has its own store connection ([open]). A caller ([submit]) waits for its command as
 * [Options.answer] says: at its append ([Answer.COMMITTED], as Wow's E1 answers, the writers saving behind) or once its
 * snapshot was written too ([Answer.SNAPSHOT], the default wait's). A store call that throws fails each command it
 * carried. [counters] counts the streams appended and the snapshots saved, so a committed step can say whether its
 * snapshots kept up.
 */
class WowShape<S : AutoCloseable>(
    private val partitions: Int,
    private val partitionOf: (Aggregates.Command) -> Int,
    private val options: Options,
    open: () -> S,
    private val append: (S, List<Aggregates.Command>) -> BooleanArray,
    private val save: (S, List<Aggregates.Command>) -> BooleanArray,
) : AutoCloseable {
    /** When a caller's command is answered. */
    enum class Answer { COMMITTED, SNAPSHOT }

    /**
     * The shape: Wow's defaults (AppendQueueOptions: two mergers, a lane's batch of 64, calls merged up to 512
     * streams; two snapshot writes in flight of up to 256 rows), [mergeMax], [appenders] and [writers] the ones a
     * what-if case varies. With [appendFill] and [saveFill], a call holds that many streams or snapshots: it waits for
     * them (FILL_WAIT_MILLIS at most) and takes no more than whole lanes' batches past them, so the calls are of a size
     * given rather than of what is there, as the calibration's equal shape is; null, as Wow's take them.
     */
    data class Options(
        val mergeMax: Int = MERGE_MAX,
        val answer: Answer = Answer.SNAPSHOT,
        val appenders: Int = APPENDERS,
        val maxBatch: Int = MAX_BATCH,
        val writers: Int = WRITERS,
        val writerMax: Int = WRITER_MAX,
        val appendFill: Int? = null,
        val saveFill: Int? = null,
    ) {
        /** The store connections the shape holds: one an appender, one a writer. */
        val connections: Int get() = appenders + writers

        /** Whether it is the shape Wow's node gives the store: its own constants, the runtime has no option for them. */
        val wows: Boolean
            get() = mergeMax == MERGE_MAX && appenders == APPENDERS && maxBatch == MAX_BATCH && writers == WRITERS &&
                writerMax == WRITER_MAX && appendFill == null && saveFill == null
    }

    private class Pending(val command: Aggregates.Command) {
        val appended = CompletableFuture<Boolean>()
        val saved = CompletableFuture<Boolean>()
    }

    private class Batch(val lane: Int, val pendings: List<Pending>) {
        val handedAt = System.nanoTime()
    }

    /** The calls' times (CallTimes): an append call and a save call, taken to answered, and a batch's wait. */
    private val times = CallTimes()

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val lanes = Array(partitions) { ArrayDeque<Pending>() }

    /** Whether a lane's batch is handed or in flight: a lane has one at most. */
    private val busy = BooleanArray(partitions)
    private val handed = ArrayDeque<Batch>()
    private val appendedCount = AtomicLong()
    private val savedCount = AtomicLong()
    private val saveFailedCount = AtomicLong()
    private val appendCalls = AtomicLong()
    private val appendPartitions = AtomicLong()
    private val saveCalls = AtomicLong()

    @Volatile
    private var closing = false

    @Volatile
    private var appendersDone = false
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

    /**
     * The streams appended and the snapshots saved so far, cumulative, and the calls that wrote them: the append calls
     * and the partitions they took streams of (a lane's batch is one partition's), and the save calls.
     */
    fun counters(): Map<String, Double> = mapOf(
        SHAPE_APPENDED to appendedCount.get().toDouble(),
        SHAPE_SAVED to savedCount.get().toDouble(),
        SHAPE_SAVE_FAILED to saveFailedCount.get().toDouble(),
        SHAPE_APPEND_CALLS to appendCalls.get().toDouble(),
        SHAPE_APPEND_PARTITIONS to appendPartitions.get().toDouble(),
        SHAPE_SAVE_CALLS to saveCalls.get().toDouble(),
    ) + times.counters()

    /** Queues [command] and waits for its answer ([Options.answer]): whether it was written. */
    fun submit(command: Aggregates.Command): Boolean {
        val pending = Pending(command)
        lock.withLock {
            check(!closing) { "The shape is closed." }
            val lane = partitionOf(command)
            lanes[lane].addLast(pending)
            hand(lane)
        }
        val answer = if (options.answer == Answer.COMMITTED) pending.appended else pending.saved
        return try {
            answer.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    /** Hands [lane]'s next batch when it has none handed or in flight: what it holds, up to a lane's batch. */
    private fun hand(lane: Int) {
        if (busy[lane] || lanes[lane].isEmpty()) return
        val batch = List(minOf(options.maxBatch, lanes[lane].size)) { lanes[lane].removeFirst() }
        busy[lane] = true
        handed.addLast(Batch(lane, batch))
        ready.signal()
    }

    private fun appendLoop(store: S) {
        while (true) {
            val batches = take() ?: return
            val takenAt = System.nanoTime()
            batches.forEach { times.add(APPEND_GAP, takenAt - it.handedAt) }
            val taken = batches.flatMap { it.pendings }
            appendCalls.incrementAndGet()
            appendPartitions.addAndGet(batches.size.toLong())
            val written = try {
                append(store, taken.map { it.command })
            } catch (failure: Exception) {
                taken.forEach {
                    it.appended.completeExceptionally(failure)
                    it.saved.completeExceptionally(failure)
                }
                null
            }
            if (written != null) {
                appendedCount.addAndGet(written.count { it }.toLong())
                taken.forEachIndexed { at, pending ->
                    pending.appended.complete(written[at])
                    if (written[at]) saves.put(pending) else pending.saved.complete(false)
                }
            }
            times.add(APPEND_CALL, System.nanoTime() - takenAt)
            lock.withLock {
                batches.forEach {
                    busy[it.lane] = false
                    hand(it.lane)
                }
            }
        }
    }

    /**
     * The next call's batches: the first handed and those handed meanwhile, each whole, while the call holds fewer
     * than the call's maximum. Waits for one; null once closed.
     */
    private fun take(): List<Batch>? = lock.withLock {
        val fill = options.appendFill
        // When the first batch was seen with a fill to wait for; null until then, and without a fill.
        var waitingSince: Long? = null
        while (!closing) {
            if (handed.isNotEmpty() && fill != null && waitingSince == null) waitingSince = System.nanoTime()
            val filled = fill == null || handed.sumOf { it.pendings.size } >= fill || waitedOut(waitingSince)
            if (handed.isNotEmpty() && filled) {
                val batches = ArrayList<Batch>()
                var streams = 0
                while (handed.isNotEmpty() && streams < (fill ?: options.mergeMax)) {
                    val batch = handed.removeFirst()
                    batches += batch
                    streams += batch.pendings.size
                }
                return batches
            }
            ready.await(if (waitingSince != null) FILL_POLL_MILLIS else POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        null
    }

    /** Whether a fill's wait that began at [since] (System.nanoTime) has run FILL_WAIT_NANOS: false when none began. */
    private fun waitedOut(since: Long?): Boolean = since != null && System.nanoTime() - since >= FILL_WAIT_NANOS

    private fun saveLoop(store: S) {
        while (!appendersDone || saves.isNotEmpty()) {
            val first = saves.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            val batch = arrayListOf(first)
            val most = options.saveFill ?: options.writerMax
            saves.drainTo(batch, most - 1)
            val since = System.nanoTime()
            while (batch.size < (options.saveFill ?: 0) && !waitedOut(since)) {
                saves.poll(FILL_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let { batch += it }
                saves.drainTo(batch, most - batch.size)
            }
            saveCalls.incrementAndGet()
            val takenAt = System.nanoTime()
            try {
                val written = save(store, batch.map { it.command })
                val saved = written.count { it }.toLong()
                savedCount.addAndGet(saved)
                saveFailedCount.addAndGet(batch.size - saved)
                batch.forEachIndexed { at, pending -> pending.saved.complete(written[at]) }
            } catch (failure: Exception) {
                saveFailedCount.addAndGet(batch.size.toLong())
                batch.forEach { it.saved.completeExceptionally(failure) }
            }
            times.add(SAVE_CALL, System.nanoTime() - takenAt)
        }
    }

    /**
     * Stops: the appenders finish their calls, then the writers save what those appended; a command still queued
     * (none, once every caller has its answer) fails. Then the store connections close.
     */
    override fun close() {
        lock.withLock {
            closing = true
            ready.signalAll()
        }
        appenders.forEach { it.join() }
        appendersDone = true
        writers.forEach { it.join() }
        val left = IllegalStateException("The shape closed before the command was written.")
        lock.withLock {
            val queued = lanes.flatMap { it } + handed.flatMap { it.pendings }
            queued.forEach {
                it.appended.completeExceptionally(left)
                it.saved.completeExceptionally(left)
            }
        }
        stores.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Wow's own constants, which the runtime gives no option for (held to them by a test): AppendQueueOptions'
         * defaults, and the snapshot writes' (SnapshotStore.DEFAULT_WRITES_IN_FLIGHT, SnapshotOptions.DEFAULT_WRITE_BATCH).
         */
        const val MERGE_MAX = 512
        const val APPENDERS = 2
        const val MAX_BATCH = 64
        const val WRITERS = 2
        const val WRITER_MAX = 256

        /** The counters' names: the streams appended, the snapshots saved. */
        const val SHAPE_APPENDED = "shapeAppended"
        const val SHAPE_SAVED = "shapeSaved"

        /** The snapshots the writers failed or the store refused: a committed caller does not see them. */
        const val SHAPE_SAVE_FAILED = "shapeSaveFailed"

        /** The calls' counters' names: the append calls, the partitions they took streams of, the save calls. */
        const val SHAPE_APPEND_CALLS = "shapeAppendCalls"
        const val SHAPE_APPEND_PARTITIONS = "shapeAppendPartitions"
        const val SHAPE_SAVE_CALLS = "shapeSaveCalls"

        /** A step's ratio of its window's saves to its appends. */
        const val SAVES_PER_APPEND = "savesPerAppend"

        /**
         * The calls' times' names (CallTimes): an append call, its batches taken to their callers answered; a batch's
         * wait, handed to taken; a save call, taken to answered.
         */
        const val APPEND_CALL = "appendCall"
        const val APPEND_GAP = "appendGap"
        const val SAVE_CALL = "saveCall"

        /** A step's partitions an append call, its window's. */
        const val PARTITIONS_PER_APPEND = "partitionsPerAppend"

        /** The share of a window's appends its saves must reach for a committed step to count as sustained. */
        const val SAVES_KEPT = 0.97
        private const val POLL_MILLIS = 50L
        private const val FILL_POLL_MILLIS = 1L
        private const val FILL_WAIT_NANOS = 20_000_000L

        /** The what-if's appenders and writers: twice Wow's, a candidate's count to price. */
        const val MORE_CALLERS = 4

        /** The calls' maxima the what-if cases price beside Wow's: one lane's batch and four lanes'. */
        val WHAT_IFS = listOf(MAX_BATCH, 256)

        /**
         * The shape's cases, each a [RawCase] with its options: Wow's own shape answered at the append (the committed
         * ceiling) and at the snapshot (the snapshot ceiling), and the what-if calls of up to 64 and 256 streams, which
         * no runtime option gives (no ceiling).
         */
        fun cases(): List<Pair<RawCase, Options>> = buildList {
            val wows = "Wow's peak shape: two appenders merging the lanes' batches to $MERGE_MAX streams, two writers " +
                "of up to $WRITER_MAX snapshots"
            add(
                RawCase("wow-shape-committed", COMMAND, 1, "$wows; answered at the append", RawCase.COMMITTED) to
                    Options(answer = Answer.COMMITTED)
            )
            add(
                RawCase("wow-shape-snapshot", COMMAND, 1, "$wows; answered once the snapshot is written", RawCase.SNAPSHOT) to
                    Options(answer = Answer.SNAPSHOT)
            )
            for (merge in WHAT_IFS) {
                add(
                    RawCase("what-if-merge-$merge", COMMAND, 1, "what-if: calls of up to $merge streams (no option gives it)") to
                        Options(mergeMax = merge)
                )
            }
            add(
                RawCase(
                    "what-if-$MORE_CALLERS-appenders-$MORE_CALLERS-writers",
                    COMMAND,
                    1,
                    "what-if: $MORE_CALLERS appenders and $MORE_CALLERS writers, Wow's $APPENDERS and $WRITERS (no option gives it)",
                ) to Options(appenders = MORE_CALLERS, writers = MORE_CALLERS)
            )
        }

        private const val COMMAND = "command"

        /** A step's shape ratios from its counters' growth: its saves per append, and its partitions an append call. */
        fun ratios(growth: Map<String, Double>): Map<String, Double> = buildMap {
            val appended = growth[SHAPE_APPENDED] ?: 0.0
            if (appended > 0) put(SAVES_PER_APPEND, (growth[SHAPE_SAVED] ?: 0.0) / appended)
            val calls = growth[SHAPE_APPEND_CALLS] ?: 0.0
            if (calls > 0) put(PARTITIONS_PER_APPEND, (growth[SHAPE_APPEND_PARTITIONS] ?: 0.0) / calls)
            putAll(CallTimes.means(growth))
        }
    }
}
