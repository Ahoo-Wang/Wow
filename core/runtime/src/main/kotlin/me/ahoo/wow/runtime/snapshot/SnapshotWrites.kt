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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.subscription.LeaderBatches
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.StoreCallMarks
import java.util.concurrent.ConcurrentHashMap

/**
 * The snapshot writes of a snapshot subscription, of all its partitions together:
 * the subscription's lanes, one per aggregate, each [save] the snapshot they built into one queue, and the saves
 * waiting together are written with one [SnapshotStore.saveAll], at most [maxBatch] at a time, by at most
 * [committers] writes at once, or [catchUp] while the writes are behind ([LeaderBatches]: who leads, the hand-over,
 * cancellation).
 *
 * - **Two writes at once**: with two committers, the store's default ([SnapshotStore.writesInFlight]), one write is
 *   in flight while the next gathers what the partitions hand meanwhile, as the log's appends merge across partitions
 *   ([me.ahoo.wow.runtime.log.AppendQueueOptions.mergers], measured 2026-10-24: with more, each call carries too
 *   little to share its fixed cost).
 * - **Behind, more writes**: a take that was full ([maxBatch]) with saves still waiting raises the writes' limit from
 *   [committers] to [catchUp] (the store's [SnapshotStore.catchUpWritesInFlight]), and a take of fewer than
 *   [retireBelow] lowers it again. So below the peak the writes stay at [committers] with their calls large, and at
 *   the peak, where every take is full, they grow to [catchUp]. At a sustained peak the limit swings between the two:
 *   a leader started for a full take first takes only what arrived since, often under [retireBelow], which lowers the
 *   limit until the next full take raises it. That is harmless: a lowered limit takes no write away (a leader over it
 *   finishes its write, then gives its place up), a leader costs one hand-over to start or end, and the writes keep up
 *   as with [catchUp] always (measured 2026-11-13). A store whose two counts are equal does none of this: its takes
 *   only compare the counts.
 * - **Coalesced**: of one aggregate's snapshots in a batch only the highest version is written; the lower ones end as
 *   it does, not written (a version-guarded save of them would change nothing).
 * - **One aggregate in one write at a time**: the subscription handles an aggregate's entries one at a time, and a
 *   save returns only once written, so two versions of one aggregate are never in two writes at once from it. Two
 *   writers of one store (a backfill beside the store's subscription, two owners during a hand-over) can still meet:
 *   the version guard then ends the earlier version `Refused` whichever lands first.
 * - **Durable before it returns**: a save returns, or fails, only once the write that covers it did, so the handler
 *   returns, and its entry is signalled and checkpointed, only after its snapshot is in the store.
 * - **Failures stay each lane's**: a snapshot the store failed alone ([SnapshotSaveOutcome.Failed]) fails its own save.
 *   A batch whose call failed fails every save of it when the store is unavailable
 *   ([StorageUnavailableException]: systemic) or with an error; otherwise its snapshots are saved again one by one,
 *   so a snapshot the store refuses, or fails with anything, parks only its own aggregate.
 * - **Stopping**: a partition stopping ([abandon]) fails its saves that wait, and a save of it that a cancelled leader
 *   would put back, with [SnapshotSaveAbandoned].
 */
internal class SnapshotWrites(
    private val store: SnapshotStore,
    maxBatch: Int,
    committers: Int = SnapshotStore.DEFAULT_WRITES_IN_FLIGHT,
    /** The most writes at once while the writes are behind: [committers] keeps the count whatever the load. */
    catchUp: Int = committers,
    /**
     * A take of fewer saves than this ends being behind: a quarter of [maxBatch], so with a write batch under four it is
     * zero, and a limit once raised stays raised.
     */
    retireBelow: Int = maxBatch / RETIRE_FRACTION,
) : LeaderBatches<Boolean, SnapshotWrites.Write>(maxBatch, committers, catchUp, retireBelow) {
    init {
        require(maxBatch >= 1) { "A snapshot write batch holds at least one snapshot." }
    }

    private val counted = SnapshotWriteCounts()

    /** Whether something read the [counts]: until then the writes count nothing. */
    @Volatile
    private var counting = false

    /**
     * What the writes took, wrote and spent ([SnapshotWriteCounts]), counted from the first call of this on. Until then
     * the writes count nothing: their only cost is reading that no one asked.
     */
    fun counts(): SnapshotWriteCounts {
        counted.limited(leadersAllowed)
        counting = true
        return counted
    }

    /** The [counts], once something read them; null until then, when nothing is counted. */
    internal val countsIfRead: SnapshotWriteCounts? get() = if (counting) counted else null

    override fun limited(leaders: Int) {
        if (counting) counted.limited(leaders)
    }

    /**
     * The partitions stopping ([abandon]): a save of one fails at once. Read under the writes' monitor, and changed
     * before [refuseWaiting] takes it, so a save either sees its partition stopping or is queued before the refusal.
     */
    private val stopping: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun refusal(request: Write): Throwable? =
        request.partition?.takeIf { it in stopping }?.let { SnapshotSaveAbandoned(it) }

    /**
     * Saves [snapshot], of [partition], with the saves waiting with it; whether it was written, as [SnapshotStore.save]
     * says. A save of a partition stopping fails with [SnapshotSaveAbandoned]; one of no partition (null: an entry
     * made by hand, with no log position) never stops.
     */
    suspend fun save(snapshot: SnapshotRecord, partition: Int? = null): Boolean = submit(Write(snapshot, partition))

    /**
     * Fails the saves of [partitions] that wait and lead nothing with [SnapshotSaveAbandoned], and every save of them
     * from now until [resume]: a stop, which waits for the writes in flight alone. A save handed the lead and the
     * writes running end as they would; the saves of other partitions are untouched.
     */
    fun abandon(partitions: Set<Int>) {
        stopping += partitions
        refuseWaiting()
    }

    /** Saves [partitions] again, once stopped: one started here again writes as before. */
    fun resume(partitions: Set<Int>) {
        stopping -= partitions
    }

    /** Writes [batch] with one [SnapshotStore.saveAll], the highest version of each aggregate only, and ends its saves. */
    override suspend fun writeBatch(batch: List<Write>) {
        val latest = LinkedHashMap<AggregateRef, Write>()
        batch.forEach { write ->
            latest.merge(write.snapshot.ref, write) { held, other ->
                if (other.snapshot.version > held.snapshot.version) other else held
            }
        }
        val writes = latest.values.toList()
        val counts = if (counting) counted else null
        val call = counts?.start(batch.size, writes.size)
        try {
            val outcomes = saved(writes, call?.marks)
            call?.marks?.returned()
            writes.forEachIndexed { index, write -> write.end(outcomes[index]) }
            batch.forEach { write ->
                val covering = latest.getValue(write.snapshot.ref)
                if (covering !== write) {
                    val outcome = outcomes[writes.indexOf(covering)]
                    write.end(if (outcome is SnapshotSaveOutcome.Failed) outcome else SnapshotSaveOutcome.Refused)
                }
            }
            call?.done()
        } finally {
            counts?.ended()
        }
    }

    /**
     * [writes] saved with one [SnapshotStore.saveAll], their outcomes; [marks], when the write is clocked, in the call's
     * context. A failure of the call is each save's, or, but for the store unavailable, each saved alone.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the store throws ends the saves of the batch, never the leader.
    private suspend fun saved(writes: List<Write>, marks: StoreCallMarks?): List<SnapshotSaveOutcome> = try {
        val snapshots = writes.map { it.snapshot }
        if (marks == null) store.saveAll(snapshots) else withContext(marks) { store.saveAll(snapshots) }
    } catch (unavailable: StorageUnavailableException) {
        // The leader's own cancellation puts the batch back (ensureActive); anything else is the store's failure.
        currentCoroutineContext().ensureActive()
        writes.map { SnapshotSaveOutcome.Failed(unavailable) }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        if (writes.size > 1) alone(writes) else listOf(SnapshotSaveOutcome.Failed(failure))
    } catch (failure: Throwable) {
        writes.map { SnapshotSaveOutcome.Failed(failure) }
    }

    /** Each of [writes] saved alone, after the batch's call failed: the failure is then the snapshot's own. */
    @Suppress("TooGenericExceptionCaught") // Any failure of one save is that snapshot's.
    private suspend fun alone(writes: List<Write>): List<SnapshotSaveOutcome> = writes.map { write ->
        try {
            SnapshotSaveOutcome.of(store.save(write.snapshot))
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            SnapshotSaveOutcome.Failed(failure)
        }
    }

    class Write(val snapshot: SnapshotRecord, val partition: Int?) : LeaderBatches.Request<Boolean>() {
        fun end(outcome: SnapshotSaveOutcome) {
            if (outcome is SnapshotSaveOutcome.Failed) {
                // A cancellation the store met is its failure, not the waiting lane's own cancellation.
                val cause = outcome.cause
                result.completeExceptionally(
                    if (cause is CancellationException) IllegalStateException(cause.message, cause) else cause,
                )
            } else {
                result.complete(outcome.written)
            }
        }

        override fun fail(cause: Throwable) = end(SnapshotSaveOutcome.Failed(cause))
    }

    private companion object {
        /** Caught up once a take is under a quarter of a batch: four's steady take at the peak is about half of one. */
        const val RETIRE_FRACTION = 4
    }
}

/** A save of [partition] given up as its partition stops: not written, so its entry's checkpoint stays before it. */
internal class SnapshotSaveAbandoned(partition: Int?) :
    IllegalStateException("The snapshot writes stop in partition $partition: the save was not written.")
