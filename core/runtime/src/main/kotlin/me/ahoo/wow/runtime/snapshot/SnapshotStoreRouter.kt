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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable

/**
 * The snapshots of a context whose aggregate types keep them on different stores (decisions D172, D177): each
 * aggregate's snapshot is in the store [stores] names for its type, else in [default].
 */
class SnapshotStoreRouter(
    private val default: SnapshotStore,
    private val stores: Map<AggregateType, SnapshotStore>,
) : SnapshotStore {
    /** The store of [type]'s snapshots. */
    fun storeOf(type: AggregateType): SnapshotStore = stores[type] ?: default

    override suspend fun save(snapshot: SnapshotRecord): Boolean = storeOf(snapshot.ref.type).save(snapshot)

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = storeOf(ref.type).load(ref)

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = storeOf(snapshot.ref.type).rewrite(snapshot)

    override suspend fun markStale(ref: AggregateRef): Boolean = storeOf(ref.type).markStale(ref)

    override suspend fun pending(
        type: AggregateType,
        revision: Int,
        after: AggregateRef?,
        limit: Int,
    ): List<AggregateRef> = storeOf(type).pending(type, revision, after, limit)

    override suspend fun table(type: AggregateType): SnapshotTable? = storeOf(type).table(type)

    override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable =
        storeOf(type).claim(type, revision, lower)

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? = storeOf(type).mark(type, revision, rebuilding, backfilled)

    companion object {
        /** [default] alone when no type keeps its snapshots elsewhere, else the router over them. */
        @JvmStatic
        fun of(default: SnapshotStore, stores: Map<AggregateType, SnapshotStore>): SnapshotStore {
            val elsewhere = stores.filterValues { it !== default }
            return if (elsewhere.isEmpty()) default else SnapshotStoreRouter(default, elsewhere)
        }
    }
}
