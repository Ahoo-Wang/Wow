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

package me.ahoo.wow.runtime.sink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord

/**
 * How much of a run's records [SinkWrites.write] made durable: the first [done] of them; when [failure] is not null,
 * the next one failed with it and the rest were not written.
 */
internal class Written(val done: Int, val failure: Throwable?)

/**
 * The writes of a sink subscription, of all its partitions together: each lane [write]s the records of a run
 * of one aggregate's entries, and the runs waiting together are written with one [Sink.write], of at most [maxBatch]
 * records, so that a warehouse gets one insert, one part or one `_bulk` for many commits instead of one each.
 *
 * - **No waiting for a batch to fill** (as [me.ahoo.wow.runtime.snapshot.SnapshotWrites]): a run finding no write
 *   running leads one at once, after a [yield] that lets the lanes ready in the same instant join it; the runs
 *   arriving while it runs wait for the next, which the leader writes too, until its own run is written. Then it hands
 *   the lead to the oldest run still waiting. A batch takes whole runs, in the order they came, and at least one.
 * - **Durable before it returns**: a run's write returns once the [Sink.write] covering its records returned, so its
 *   entries are signalled and checkpointed only once their records are durable. The records of one aggregate keep
 *   their order: its lane writes its next run only after this one returned.
 * - **Failures stay each aggregate's**: a batch the sink failed with [StorageUnavailableException] fails every run of
 *   it (systemic: the partitions pause). Any other failure of a batch of several runs writes each run alone, and a
 *   run that fails alone is written record by record: it answers the records written before the one that failed, and
 *   that one's failure, so only its aggregate is retried and parked, from that record. A fatal error fails every run.
 * - **Cancellation**: a leader cancelled (its lane stopped, or its run timed out) leaves the queue if no batch took its
 *   own run yet, puts the runs it took back in front and hands the lead on; a waiting run cancelled leaves the queue.
 */
internal class SinkWrites(private val sink: Sink, private val maxBatch: Int) {
    init {
        require(maxBatch >= 1) { "A sink write batch holds at least one record." }
    }

    private val queue = ArrayDeque<Run>()

    /** Whether a run leads the writes, or was handed the lead. Guarded by this object's monitor. */
    private var leading = false

    /** The runs waiting now, for a test. */
    val waiting: Int get() = synchronized(this) { queue.size }

    /** Writes [records], a run of one aggregate's, with the runs waiting with it; how many are durable. */
    suspend fun write(records: List<StateEventRecord>): Written {
        if (records.isEmpty()) {
            return Written(0, null)
        }
        val run = Run(records)
        val leads = synchronized(this) {
            queue.addLast(run)
            (!leading).also { leading = true }
        }
        if (!leads && !awaitLead(run)) {
            return run.result.await()
        }
        lead(run)
        return run.result.await()
    }

    /** Waits until [run] is written, false, or handed the lead, true. */
    private suspend fun awaitLead(run: Run): Boolean = try {
        select {
            run.result.onAwait { false }
            run.lead.onAwait { true }
        }
    } catch (cancelled: CancellationException) {
        synchronized(this) {
            if (queue.remove(run) && run.lead.isCompleted) {
                // Handed the lead it will not take.
                handOver()
            }
        }
        throw cancelled
    }

    /** Writes the waiting runs, a batch at a time, until [own] is written; then hands the lead on. */
    private suspend fun lead(own: Run) {
        var taken = emptyList<Run>()
        try {
            yield()
            while (!own.result.isCompleted) {
                // The own run waits in the queue until a batch takes it, so a batch is never empty.
                taken = synchronized(this) { batch() }
                writeBatch(taken)
            }
        } catch (cancelled: CancellationException) {
            synchronized(this) {
                // Cancelled at the yield, its own run still heads the queue: handed the lead, it would lead nobody.
                queue.remove(own)
                // Cancelled in a write, before it ended any run of its batch: the others go back in front.
                taken.filter { it !== own }.asReversed().forEach(queue::addFirst)
            }
            throw cancelled
        } finally {
            synchronized(this) { handOver() }
        }
    }

    /** Called under the monitor: the runs at the queue's head whose records fit [maxBatch] together, at least one. */
    private fun batch(): List<Run> {
        val taken = mutableListOf(queue.removeFirst())
        var size = taken.single().records.size
        while (queue.isNotEmpty() && size + queue.first().records.size <= maxBatch) {
            size += queue.first().records.size
            taken += queue.removeFirst()
        }
        return taken
    }

    /** Called under the monitor: the lead passes to the oldest waiting run, or no run leads. */
    private fun handOver() {
        val next = queue.firstOrNull()
        if (next == null) {
            leading = false
        } else {
            next.lead.complete(Unit)
        }
    }

    /** Writes [batch] with one [Sink.write], and ends its runs. */
    @Suppress("TooGenericExceptionCaught") // Whatever the sink throws ends the runs of the batch, never the leader.
    private suspend fun writeBatch(batch: List<Run>) {
        // The leader's own cancellation puts the batch back (ensureActive); anything else is the sink's failure.
        try {
            sink.write(batch.flatMap { it.records })
            batch.forEach { it.end(Written(it.records.size, null)) }
        } catch (unavailable: StorageUnavailableException) {
            currentCoroutineContext().ensureActive()
            batch.forEach { it.end(Written(0, unavailable)) }
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            when {
                NodeFatals.isFatal(failure) -> batch.forEach { it.end(Written(0, failure)) }
                batch.size > 1 -> batch.forEach { it.end(alone(it.records)) }
                else -> batch.single().end(recordByRecord(batch.single().records, failure))
            }
        }
    }

    /** [records], one run's, written alone after its batch failed: the failure is then the run's own. */
    @Suppress("TooGenericExceptionCaught") // Any failure of one run's write is that run's.
    private suspend fun alone(records: List<StateEventRecord>): Written = try {
        sink.write(records)
        Written(records.size, null)
    } catch (unavailable: StorageUnavailableException) {
        currentCoroutineContext().ensureActive()
        Written(0, unavailable)
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        if (NodeFatals.isFatal(failure)) Written(0, failure) else recordByRecord(records, failure)
    }

    /**
     * [records], one run's that failed with [failure], written one at a time until one fails: how many were, and that
     * one's failure. A run of one record failed already.
     */
    @Suppress("TooGenericExceptionCaught") // A record's failure is its answer.
    private suspend fun recordByRecord(records: List<StateEventRecord>, failure: Throwable): Written {
        if (records.size == 1) {
            return Written(0, failure)
        }
        records.forEachIndexed { index, record ->
            try {
                sink.write(listOf(record))
            } catch (own: Throwable) {
                currentCoroutineContext().ensureActive()
                return Written(index, own)
            }
        }
        return Written(records.size, null)
    }

    private class Run(val records: List<StateEventRecord>) {
        val result = CompletableDeferred<Written>()

        /** Completed when the lead is handed to this run. */
        val lead = CompletableDeferred<Unit>()

        fun end(written: Written) {
            // A cancellation the sink met is its failure, not the waiting lane's own cancellation.
            val failure = written.failure
            result.complete(
                if (failure is CancellationException) {
                    Written(written.done, IllegalStateException(failure.message, failure))
                } else {
                    written
                },
            )
        }
    }
}
