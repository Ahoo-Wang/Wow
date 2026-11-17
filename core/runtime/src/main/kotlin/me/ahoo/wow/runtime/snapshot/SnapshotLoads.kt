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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.subscription.LeaderBatches
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The snapshot loads of a snapshot subscription's entries without a view, of all its partitions together: each lane
 * [load]s into one queue, and the loads waiting together are read with one [SnapshotStore.loadAll], at most [maxBatch]
 * at a time, by at most [callers] calls at once ([LeaderBatches]: who leads, the hand-over, cancellation).
 *
 * - **Failures stay each lane's**: a call that fails with the store unavailable fails every load of it; with anything
 *   else, its loads are read again one by one, so a snapshot the store fails to read fails only its own entry.
 * - **Stopping**: a partition stopping ([abandon]) fails its loads that wait with [SnapshotLoadAbandoned]; the calls
 *   running end as they would.
 */
internal class SnapshotLoads(
    private val store: SnapshotStore,
    maxBatch: Int,
    callers: Int,
    /** Where a call's loads are counted, once something reads the counts. */
    private val counts: () -> SnapshotWriteCounts?,
) : LeaderBatches<SnapshotRecord?, SnapshotLoads.Load>(maxBatch, callers, callers, 0) {
    init {
        require(maxBatch >= 1) { "A snapshot load batch holds at least one load." }
    }

    private val stopping: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun refusal(request: Load): Throwable? =
        request.partition?.takeIf { it in stopping }?.let { SnapshotLoadAbandoned(it) }

    /** The snapshot of [ref], of [partition], read with the loads waiting with it. */
    suspend fun load(ref: AggregateRef, partition: Int? = null): SnapshotRecord? = submit(Load(ref, partition))

    /** Fails the loads of [partitions] that wait, and every load of them until [resume]: a stop. */
    fun abandon(partitions: Set<Int>) {
        stopping += partitions
        refuseWaiting()
    }

    /** Loads [partitions] again, once stopped. */
    fun resume(partitions: Set<Int>) {
        stopping -= partitions
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the store throws ends the loads of the batch, never the leader.
    override suspend fun writeBatch(batch: List<Load>) {
        val refs = batch.mapTo(LinkedHashSet()) { it.ref }
        val loaded = try {
            store.loadAll(refs)
        } catch (unavailable: StorageUnavailableException) {
            currentCoroutineContext().ensureActive()
            batch.forEach { it.fail(unavailable) }
            return
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (refs.size > 1) alone(batch) else batch.forEach { it.fail(failure) }
            return
        }
        counts()?.loaded(refs.size)
        batch.forEach { it.result.complete(loaded[it.ref]) }
    }

    /** Each of [batch] loaded alone, after the batch's call failed: the failure is then the load's own. */
    @Suppress("TooGenericExceptionCaught") // Any failure of one load is that entry's.
    private suspend fun alone(batch: List<Load>) {
        batch.forEach { load ->
            try {
                val snapshot = store.load(load.ref)
                counts()?.loaded(1)
                load.result.complete(snapshot)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                load.fail(failure)
            }
        }
    }

    class Load(val ref: AggregateRef, val partition: Int?) : LeaderBatches.Request<SnapshotRecord?>() {
        override fun fail(cause: Throwable) {
            // A cancellation the store met is its failure, not the waiting lane's own cancellation.
            result.completeExceptionally(
                if (cause is CancellationException) IllegalStateException(cause.message, cause) else cause,
            )
        }
    }
}

/** A load of [partition] given up as its partition stops: its entry is handled again by the next owner. */
internal class SnapshotLoadAbandoned(partition: Int?) :
    IllegalStateException("The snapshot loads stop in partition $partition: the load was not read.")
