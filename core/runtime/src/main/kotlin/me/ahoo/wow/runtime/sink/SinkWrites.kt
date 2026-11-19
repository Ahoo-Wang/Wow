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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.subscription.LeaderBatches
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import kotlin.time.Duration
import kotlin.time.TimeSource

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
 * - **One per sink, one write in flight**: the batch grows with the load, up to [maxBatch], whatever partition each
 *   run is of. Group commits by partition would only cut it: on a sink that writes one batch at a time (ClickHouse's
 *   one insert), four of them carried half the commits an insert one did, draining a backlog.
 * - **Paced** ([Sink.minWriteInterval]): a write starts no sooner than the sink's interval after the previous one
 *   started, unless [maxBatch] records are waiting, and the runs arriving meanwhile join it. Without it, a sink whose
 *   write is fast is written as often as it returns, each write carrying only what arrived during the last: the
 *   ClickHouse sink's inserts took 7 ms under the rc drill's load, 3,250 a minute of a few rows each (#6494).
 *
 * - **One leader, as [LeaderBatches] with one committer**: a run finding no write running leads one at once; the runs
 *   arriving while it runs wait for the next, which the leader writes too, until its own run is written. Then it hands
 *   the lead to the oldest run still waiting. A batch takes whole runs, in the order they came, and at least one.
 * - **Durable before it returns**: a run's write returns once the [Sink.write] covering its records returned, so its
 *   entries are signalled and checkpointed only once their records are durable. The records of one aggregate keep
 *   their order: its lane writes its next run only after this one returned.
 * - **Failures stay each aggregate's**: a batch the sink failed with [StorageUnavailableException] fails every run of
 *   it (systemic: the partitions pause). Any other failure of a batch of several runs writes each run alone, and a
 *   run that fails alone is written record by record: it answers the records written before the one that failed, and
 *   that one's failure, so only its aggregate is retried and parked, from that record. A fatal error fails every run.
 *   The leader writes them within its own run's timeout: at most [maxBatch] records, as the batch was. A leader that
 *   times out puts the runs it has not ended back, and the next leader goes on from them. The entry that failed is
 *   then handled alone by its lane, with one [Sink.write] of its own outside these batches, so no later batch
 *   carries it.
 */
internal class SinkWrites(
    private val sink: Sink,
    maxBatch: Int,
    interval: Duration = Duration.ZERO,
    time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : LeaderBatches<Written, SinkWrites.Run>(maxBatch, committers = 1, catchUp = 1, retireBelow = 0, interval, time) {
    init {
        require(maxBatch >= 1) { "A sink write batch holds at least one record." }
    }

    /** Writes [records], a run of one aggregate's, with the runs waiting with it; how many are durable. */
    suspend fun write(records: List<StateEventRecord>): Written =
        if (records.isEmpty()) Written(0, null) else submit(Run(records))

    /** Writes [batch] with one [Sink.write], and ends its runs. */
    @Suppress("TooGenericExceptionCaught") // Whatever the sink throws ends the runs of the batch, never the leader.
    override suspend fun writeBatch(batch: List<Run>) {
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

    class Run(val records: List<StateEventRecord>) : LeaderBatches.Request<Written>() {
        override val size: Int get() = records.size

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

        override fun fail(cause: Throwable) = end(Written(0, cause))
    }
}
