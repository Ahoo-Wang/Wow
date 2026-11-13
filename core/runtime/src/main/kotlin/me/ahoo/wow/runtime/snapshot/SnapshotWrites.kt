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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.StoreCallMarks

/**
 * The snapshot writes of a snapshot subscription, of all its partitions together:
 * the subscription's lanes, one per aggregate, each [save] the snapshot they built into one queue, and the saves
 * waiting together are written with one [SnapshotStore.saveAll], at most [maxBatch] at a time, by at most
 * [committers] writes at once, or [catchUp] while the writes are behind.
 *
 * - **No waiting for a batch to fill** (as the log's group commit): a save finding fewer than [committers] writes
 *   running leads one at once, after a [yield] that lets the lanes ready in the same instant join it; the saves
 *   arriving while the writes run wait for the next, which a leader writes too, until its own save is written. Then it
 *   hands the lead to the oldest save still waiting, so each lane goes on as soon as its own snapshot is durable. With
 *   two committers, the store's default ([SnapshotStore.writesInFlight]), one write is in flight while the next gathers
 *   what the partitions hand meanwhile, as the log's appends merge across partitions
 *   ([me.ahoo.wow.runtime.log.AppendQueueOptions.mergers], measured 2026-10-24: with more, each call carries too
 *   little to share its fixed cost).
 * - **A leader writes its own save**: a save leading is taken by no other leader's batch, so a leader's batch always
 *   holds its own save until it is written.
 * - **Behind, more writes**: a take that was full ([maxBatch]) with saves still waiting raises the writes' limit from
 *   [committers] to [catchUp], and the take starts another leader at once ([startWaiting]), one a take while they stay
 *   full; a take of fewer than [retireBelow] lowers it again, and a leader over the limit then gives its place up when
 *   it ends instead of handing it on. So below the peak the writes stay at [committers] with their calls large, and at
 *   the peak, where every take is full, they grow to [catchUp] (the store's [SnapshotStore.catchUpWritesInFlight]).
 * - **The invariant**, at every instant the monitor is free: a save waits without leading only while the leaders are
 *   at the limit or one gathers. Each change of the queue and of the leaders keeps it in one critical section:
 *   a save that leads or waits, a take (with [startWaiting] after it), a hand-over, and a cancelled leader's put-back
 *   with its hand-over. So a leader is the oldest save not leading when it gathers, its first batch holds its own
 *   save, and the only leading save it meets in the queue is its own. The guards that would cope otherwise (a leader's
 *   later batch, another leader's save skipped by a take, a gatherer met by [startWaiting]) are cheap checks of it,
 *   not paths the writes rely on.
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
 *   ([StorageUnavailableException]: systemic); otherwise its snapshots are saved again one by one, so
 *   a snapshot the store refuses parks only its own aggregate.
 * - **Cancellation**: a leader cancelled (its lane stopped, or its handler timed out) leaves the queue if no batch
 *   took its own save yet, puts the saves it took back in front and hands the lead on; a waiting save cancelled
 *   leaves the queue.
 */
internal class SnapshotWrites(
    private val store: SnapshotStore,
    private val maxBatch: Int,
    private val committers: Int = SnapshotStore.DEFAULT_WRITES_IN_FLIGHT,
    /** The most writes at once while the writes are behind: [committers] keeps the count whatever the load. */
    private val catchUp: Int = committers,
    /** A take of fewer saves than this ends being behind. */
    private val retireBelow: Int = maxBatch / RETIRE_FRACTION,
) {
    init {
        require(maxBatch >= 1) { "A snapshot write batch holds at least one snapshot." }
        require(committers >= 1) { "Snapshot writes take at least one committer." }
        require(catchUp >= committers) { "Snapshot writes catch up with at least their committers." }
    }

    /** How many saves may lead now: [committers], or [catchUp] while behind. Guarded by this object's monitor. */
    private var limit = committers

    private val queue = ArrayDeque<Write>()

    /** How many saves lead the writes, or were handed the lead. Guarded by this object's monitor. */
    private var leaders = 0

    /**
     * Whether a leader is gathering its first batch, between its lead and its first take. Guarded by this object's
     * monitor. One leader gathers at a time, so the saves ready together are one batch; a second write starts only once
     * the first took its batch, and carries what arrived since.
     */
    private var gathering = false

    /** The saves waiting now, for a test. */
    val waiting: Int get() = synchronized(this) { queue.size }

    private val counted = SnapshotWriteCounts()

    /** Whether something read the [counts]: until then the writes count nothing. */
    @Volatile
    private var counting = false

    /**
     * What the writes took, wrote and spent ([SnapshotWriteCounts]), counted from the first call of this on. Until then
     * the writes count nothing: their only cost is reading that no one asked.
     */
    fun counts(): SnapshotWriteCounts {
        synchronized(this) { counted.limited(limit) }
        counting = true
        return counted
    }

    /** Saves [snapshot] with the saves waiting with it; whether it was written, as [SnapshotStore.save] says. */
    suspend fun save(snapshot: SnapshotRecord): Boolean {
        val write = Write(snapshot)
        val leads = synchronized(this) {
            queue.addLast(write)
            (leaders < limit && !gathering).also {
                if (it) {
                    leaders++
                    gathering = true
                    write.leads = true
                }
            }
        }
        if (!leads && !awaitLead(write)) {
            return write.result.await()
        }
        lead(write)
        return write.result.await()
    }

    /** Waits until [write] is written, false, or handed the lead, true. */
    private suspend fun awaitLead(write: Write): Boolean = try {
        select {
            write.result.onAwait { false }
            write.lead.onAwait { true }
        }
    } catch (cancelled: CancellationException) {
        synchronized(this) {
            if (queue.remove(write) && write.leads) {
                // Handed the lead it will not take: it was gathering.
                handOver(wasGathering = true)
            }
        }
        throw cancelled
    }

    /** Writes the waiting saves, a batch at a time, until [own] is written; then hands the lead on. */
    private suspend fun lead(own: Write) {
        var taken = emptyList<Write>()
        var gathered = false
        var handedOn = false
        try {
            yield()
            while (!own.result.isCompleted) {
                // The own save waits in the queue until this leader's batch takes it, so a batch is never empty.
                taken = synchronized(this) {
                    take(own).also {
                        if (!gathered) {
                            gathered = true
                            gathering = false
                        }
                        paced(it.size)
                        startWaiting()
                    }
                }
                write(taken)
            }
        } catch (cancelled: CancellationException) {
            // One critical section: the saves put back are never seen waiting with a place free and nobody gathering.
            synchronized(this) {
                // Cancelled at the yield, its own save still heads the queue: handed the lead, it would lead nobody.
                queue.remove(own)
                // Cancelled in a write, before it ended any save of its batch: the others go back in front.
                taken.filter { it !== own }.asReversed().forEach(queue::addFirst)
                handOver(wasGathering = !gathered)
                handedOn = true
            }
            throw cancelled
        } finally {
            if (!handedOn) {
                synchronized(this) { handOver(wasGathering = !gathered) }
            }
        }
    }

    /**
     * Called under the monitor: the oldest waiting saves, at most [maxBatch], taken out of the queue for [own]'s
     * leader; [own] and the saves no other leader leads.
     */
    private fun take(own: Write): List<Write> {
        val taken = ArrayList<Write>(minOf(maxBatch, queue.size))
        val iterator = queue.iterator()
        while (taken.size < maxBatch && iterator.hasNext()) {
            val write = iterator.next()
            if (write === own || !write.leads) {
                taken += write
                iterator.remove()
            }
        }
        return taken
    }

    /**
     * Called under the monitor, after a take of [taken] saves: behind when it was full with saves still waiting, the
     * limit up to [catchUp]; caught up when it took fewer than [retireBelow], the limit back to [committers].
     */
    private fun paced(taken: Int) {
        if (taken >= maxBatch && queue.any { !it.leads }) {
            limit = catchUp
        } else if (taken < retireBelow) {
            limit = committers
        }
        if (counting) counted.limited(limit)
    }

    /**
     * Called under the monitor, after a take: while no leader gathers and fewer than the limit lead, the oldest save
     * still waiting leads the next write and gathers. So the saves a take left behind (more than [maxBatch] waiting,
     * or arrived while another leader gathered) get a write of their own at once, never sitting behind a single write
     * until it ends.
     */
    private fun startWaiting() {
        if (gathering || leaders >= limit) {
            return
        }
        val next = queue.firstOrNull { !it.leads } ?: return
        leaders++
        next.leads = true
        gathering = true
        next.lead.complete(Unit)
    }

    /**
     * Called under the monitor: a leader's place passes to the oldest save waiting, not leading, which then gathers;
     * else, while another leader gathers (which takes the waiting saves), or while more lead than the limit (it fell
     * when the writes caught up), it is given up. [wasGathering]: the leader leaving had not taken its first batch yet.
     */
    private fun handOver(wasGathering: Boolean) {
        if (wasGathering) {
            gathering = false
        }
        val next = queue.firstOrNull { !it.leads }
        if (next == null || gathering || leaders > limit) {
            leaders--
        } else {
            next.leads = true
            gathering = true
            next.lead.complete(Unit)
        }
    }

    /** Writes [batch] with one [SnapshotStore.saveAll], the highest version of each aggregate only, and ends its saves. */
    private suspend fun write(batch: List<Write>) {
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
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            SnapshotSaveOutcome.Failed(failure)
        }
    }

    private class Write(val snapshot: SnapshotRecord) {
        val result = CompletableDeferred<Boolean>()

        /** Whether this save leads the writes, or was handed the lead. Guarded by the writes' monitor. */
        var leads = false

        /** Completed when the lead is handed to this save. */
        val lead = CompletableDeferred<Unit>()

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
    }

    private companion object {
        /** Caught up once a take is under a quarter of a batch: four's steady take at the peak is about half of one. */
        const val RETIRE_FRACTION = 4
    }
}
