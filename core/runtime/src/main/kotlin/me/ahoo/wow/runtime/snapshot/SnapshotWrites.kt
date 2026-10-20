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
import kotlinx.coroutines.yield
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * The snapshot writes of one partition of a snapshot subscription (decision D248 ②): the subscription's lanes, one per
 * aggregate, each [save] the snapshot they built, and the saves waiting together are written with one
 * [SnapshotStore.saveAll], at most [maxBatch] at a time.
 *
 * - **No waiting for a batch to fill** (as the log's group commit, decision D11): a save finding no write running
 *   leads one at once, after a [yield] that lets the lanes ready in the same instant join it; the saves arriving while
 *   it runs wait for the next, which the leader writes too, until its own save is written. Then it hands the lead to
 *   the oldest save still waiting, so each lane goes on as soon as its own snapshot is durable.
 * - **Coalesced**: of one aggregate's snapshots in a batch only the highest version is written; the lower ones end as
 *   it does, not written (a version-guarded save of them would change nothing).
 * - **Durable before it returns**: a save returns, or fails, only once the write that covers it did, so the handler
 *   returns, and its entry is signalled and checkpointed, only after its snapshot is in the store.
 * - **Failures stay each lane's**: a snapshot the store failed alone ([SnapshotSaveOutcome.Failed]) fails its own save.
 *   A batch whose call failed fails every save of it when the store is unavailable
 *   ([StorageUnavailableException]: systemic, decision D243); otherwise its snapshots are saved again one by one, so
 *   a snapshot the store refuses parks only its own aggregate.
 * - **Cancellation**: a leader cancelled (its lane stopped, or its handler timed out) puts the saves it took back in
 *   front and hands the lead on; a waiting save cancelled leaves the queue.
 */
internal class SnapshotWrites(private val store: SnapshotStore, private val maxBatch: Int) {
    init {
        require(maxBatch >= 1) { "A snapshot write batch holds at least one snapshot." }
    }

    private val queue = ArrayDeque<Write>()

    /** Whether a save leads the writes, or was handed the lead. Guarded by this object's monitor. */
    private var leading = false

    /** The saves waiting now, for a test. */
    val waiting: Int get() = synchronized(this) { queue.size }

    /** Saves [snapshot] with the saves waiting with it; whether it was written, as [SnapshotStore.save] says. */
    suspend fun save(snapshot: SnapshotRecord): Boolean {
        val write = Write(snapshot)
        val leads = synchronized(this) {
            queue.addLast(write)
            (!leading).also { leading = true }
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
            if (queue.remove(write) && write.lead.isCompleted) {
                // Handed the lead it will not take.
                handOver()
            }
        }
        throw cancelled
    }

    /** Writes the waiting saves, a batch at a time, until [own] is written; then hands the lead on. */
    private suspend fun lead(own: Write) {
        var taken = emptyList<Write>()
        try {
            yield()
            while (!own.result.isCompleted) {
                // The own save waits in the queue until a batch takes it, so a batch is never empty.
                taken = synchronized(this) { List(minOf(maxBatch, queue.size)) { queue.removeFirst() } }
                write(taken)
            }
        } catch (cancelled: CancellationException) {
            // Cancelled in a write, before it ended any save of its batch.
            synchronized(this) { taken.filter { it !== own }.asReversed().forEach(queue::addFirst) }
            throw cancelled
        } finally {
            synchronized(this) { handOver() }
        }
    }

    /** Called under the monitor: the lead passes to the oldest waiting save, or no save leads. */
    private fun handOver() {
        val next = queue.firstOrNull()
        if (next == null) {
            leading = false
        } else {
            next.lead.complete(Unit)
        }
    }

    /** Writes [batch] with one [SnapshotStore.saveAll], the highest version of each aggregate only, and ends its saves. */
    @Suppress("TooGenericExceptionCaught") // Whatever the store throws ends the saves of the batch, never the leader.
    private suspend fun write(batch: List<Write>) {
        val latest = LinkedHashMap<AggregateRef, Write>()
        batch.forEach { write ->
            latest.merge(write.snapshot.ref, write) { held, other ->
                if (other.snapshot.version > held.snapshot.version) other else held
            }
        }
        val writes = latest.values.toList()
        // The leader's own cancellation puts the batch back (ensureActive); anything else is the store's failure.
        val outcomes = try {
            store.saveAll(writes.map { it.snapshot })
        } catch (unavailable: StorageUnavailableException) {
            currentCoroutineContext().ensureActive()
            writes.map { SnapshotSaveOutcome.Failed(unavailable) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (writes.size > 1) alone(writes) else listOf(SnapshotSaveOutcome.Failed(failure))
        } catch (failure: Throwable) {
            writes.map { SnapshotSaveOutcome.Failed(failure) }
        }
        writes.forEachIndexed { index, write -> write.end(outcomes[index]) }
        batch.forEach { write ->
            val covering = latest.getValue(write.snapshot.ref)
            if (covering !== write) {
                val outcome = outcomes[writes.indexOf(covering)]
                write.end(if (outcome is SnapshotSaveOutcome.Failed) outcome else SnapshotSaveOutcome.Refused)
            }
        }
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
}
