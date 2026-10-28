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

package me.ahoo.wow.spi.snapshot

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.coroutines.cancellation.CancellationException

/**
 * The snapshots of one bounded context: each aggregate's latest committed state, the default read model (axiom
 * A11, documentation/designs/03-log-and-snapshot.md §3). A snapshot is derived from the log and can be rebuilt
 * from it, so it is written outside the append and a lost write costs only a rebuild.
 *
 * The contract every backend keeps, checked by the TCK:
 * - the key is the `ref`, which includes the tenant: two tenants may hold a snapshot of the same aggregate id;
 * - [save] is version-guarded: a snapshot replaces the stored one only when its version is higher, so writers that
 *   race or arrive out of order always leave the highest version;
 * - a snapshot never replaces one of a higher state revision ([SnapshotRecord.stateRevision]): a node
 *   of the older code, during a rolling upgrade or after a rollback began, does not overwrite what the newer code
 *   wrote. A store with [rebuilds] marks the stored snapshot stale instead, so the rebuild brings it up to the version
 *   the log holds;
 * - [saveAll] saves several, each as [save] would, and says how each ended;
 * - there is no delete: a deleted aggregate keeps its snapshot with [SnapshotRecord.deleted] set.
 *
 * What a backend must implement are the abstract members; [saveAll] is an optional optimization whose default is
 * correct, and the rebuild control plane is an optional capability of its own port, [rebuilds].
 */
@WowSpi
interface SnapshotStore {
    /**
     * Stores [snapshot] if the store holds none for its ref, or holds a lower version of the same or a lower state
     * revision; returns whether it was written. A snapshot of the same or a lower version is not written and is not an
     * error. One of a higher version refused because the stored one is of a higher state revision marks the stored
     * one stale ([SnapshotRebuildStore.markStale]) when the store has [rebuilds].
     */
    suspend fun save(snapshot: SnapshotRecord): Boolean

    /**
     * Saves [snapshots], each exactly as [save] would (the version guard, a stale mark for a higher state revision),
     * and returns their outcomes in the same order: the snapshot subscription writes what it
     * coalesced from a batch of entries with one call, so a backend may write them together to share the round trips.
     * A batch that holds a ref more than once is applied as saving its snapshots in that order
     * ([SnapshotSaveOutcome.rounds]). A snapshot the backend fails to save alone is [SnapshotSaveOutcome.Failed]; a
     * failure of the whole call, the database unreachable say, is thrown. By default they are saved one by one.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever one save throws is that snapshot's failure.
    suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> = snapshots.map { snapshot ->
        try {
            SnapshotSaveOutcome.of(save(snapshot))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SnapshotSaveOutcome.Failed(failure)
        }
    }

    /** The snapshot of [ref], or null when there is none. */
    suspend fun load(ref: AggregateRef): SnapshotRecord?

    /**
     * The store's snapshot rebuild control plane: an optional capability, null by default, for a store
     * that cannot rewrite its snapshots in place. The node then leaves the snapshots of another state revision as they
     * are, which the kernel reads past (it replays the log), until a commit replaces them; it warns once and counts the
     * fallback.
     */
    val rebuilds: SnapshotRebuildStore? get() = null
}
