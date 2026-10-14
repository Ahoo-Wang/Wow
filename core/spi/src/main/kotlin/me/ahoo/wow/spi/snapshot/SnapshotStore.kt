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
import me.ahoo.wow.api.aggregate.AggregateType

/**
 * The snapshots of one bounded context: each aggregate's latest committed state, the default read model (V10 axiom
 * A11, documentation/designs/v10/03-log-and-snapshot.md §3). A snapshot is derived from the log and can be rebuilt
 * from it, so it is written outside the append and a lost write costs only a rebuild.
 *
 * The contract every backend keeps, checked by the TCK:
 * - the key is the `ref`, which includes the tenant: two tenants may hold a snapshot of the same aggregate id;
 * - [save] is version-guarded: a snapshot replaces the stored one only when its version is higher, so writers that
 *   race or arrive out of order always leave the highest version;
 * - a snapshot never replaces one of a higher state revision ([SnapshotRecord.stateRevision], decision D172): a node
 *   of the older code, during a rolling upgrade or after a rollback began, does not overwrite what the newer code
 *   wrote. Its save marks the stored snapshot stale instead, so the rebuild brings it up to the version the log holds;
 * - there is no delete: a deleted aggregate keeps its snapshot with [SnapshotRecord.deleted] set.
 *
 * **Rebuilds** (decision D172) work per aggregate type, its table: the store records the state revision the table is
 * claimed at ([claim], [table]), and the snapshots of another revision or marked stale ([markStale]) are [pending]
 * until a rebuild [rewrite]s them from the log, in place, whatever their revision.
 */
interface SnapshotStore {
    /**
     * Stores [snapshot] if the store holds none for its ref, or holds a lower version of the same or a lower state
     * revision; returns whether it was written. A snapshot of the same or a lower version is not written and is not an
     * error. One of a higher version refused because the stored one is of a higher state revision marks the stored
     * one stale ([markStale]).
     */
    suspend fun save(snapshot: SnapshotRecord): Boolean

    /** The snapshot of [ref], or null when there is none. */
    suspend fun load(ref: AggregateRef): SnapshotRecord?

    /**
     * Stores [snapshot], rebuilt from the log, in place of the stored one (decision D172): when the store holds none
     * for its ref, or holds the same or a lower version, whatever its state revision; returns whether it was written.
     * A stored version above [snapshot]'s, written meanwhile, stays. Writing clears a stale mark of a version up to
     * [snapshot]'s.
     */
    suspend fun rewrite(snapshot: SnapshotRecord): Boolean

    /**
     * Marks the snapshot of [ref] stale, so a rebuild rewrites it from the log ([pending]) whatever its state
     * revision; false when there is none.
     */
    suspend fun markStale(ref: AggregateRef): Boolean

    /**
     * The refs of [type]'s snapshots a rebuild at state [revision] has to rewrite: those of another state revision,
     * and those marked stale; at most [limit] of them, in key order (tenant, then aggregate id, by code point), after
     * [after] when it is given.
     */
    suspend fun pending(type: AggregateType, revision: Int, after: AggregateRef? = null, limit: Int): List<AggregateRef>

    /** The record of [type]'s table: the state revision it is claimed at and how far it is; null before any [claim]. */
    suspend fun table(type: AggregateType): SnapshotTable?

    /**
     * Claims [type]'s table at state [revision] and returns its record after the claim (decision D172). The first
     * claim records the table, not yet backfilled, and rebuilding unless [revision] is 1 (a snapshot written before
     * revisions is of revision 1). A later claim raises a lower revision; it lowers a higher one only when [lower]
     * says so (a rollback, once no node of the higher revision is left); either change starts a rebuild. Claims are
     * atomic: concurrent claims leave one revision.
     */
    suspend fun claim(type: AggregateType, revision: Int, lower: Boolean = false): SnapshotTable

    /**
     * Sets [rebuilding] and [backfilled], each when given, on [type]'s table while it is claimed at [revision]; returns
     * the record after, or null when the table is claimed at another revision or not at all.
     */
    suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean? = null,
        backfilled: Boolean? = null,
    ): SnapshotTable?
}

/**
 * The record of one aggregate type's snapshot table (decision D172): the state [revision] it is claimed at, whether
 * snapshots of another revision or marked stale may remain to be rewritten ([rebuilding]), and whether the table has
 * caught up with the log since it was first claimed ([backfilled]): a snapshot target added to a running service is
 * filled from the beginning of the log. A query of a table that is [catchingUp] answers with `rebuilding`.
 */
data class SnapshotTable(val revision: Int, val rebuilding: Boolean = false, val backfilled: Boolean = true) {
    init {
        require(revision >= 1) { "A state revision starts at 1." }
    }

    /** Whether the table is rebuilding or not backfilled yet: a query of it answers with `rebuilding`. */
    val catchingUp: Boolean get() = rebuilding || !backfilled
}
