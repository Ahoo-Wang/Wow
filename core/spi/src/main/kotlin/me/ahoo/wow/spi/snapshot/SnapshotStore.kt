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

/**
 * The snapshots of one bounded context: each aggregate's latest committed state, the default read model (V10 axiom
 * A11, documentation/designs/v10/03-log-and-snapshot.md §3). A snapshot is derived from the log and can be rebuilt
 * from it, so it is written outside the append and a lost write costs only a rebuild.
 *
 * The contract every backend keeps, checked by the TCK:
 * - the key is the `ref`, which includes the tenant: two tenants may hold a snapshot of the same aggregate id;
 * - [save] is version-guarded: a snapshot replaces the stored one only when its version is higher, so writers that
 *   race or arrive out of order always leave the highest version;
 * - there is no delete: a deleted aggregate keeps its snapshot with [SnapshotRecord.deleted] set.
 */
interface SnapshotStore {
    /**
     * Stores [snapshot] if the store holds none for its ref or holds a lower version; returns whether it was written.
     * A snapshot of the same or a lower version is not written and is not an error.
     */
    suspend fun save(snapshot: SnapshotRecord): Boolean

    /** The snapshot of [ref], or null when there is none. */
    suspend fun load(ref: AggregateRef): SnapshotRecord?
}
