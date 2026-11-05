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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable

/**
 * The snapshots of a context whose aggregate types keep them on different stores: each
 * aggregate's snapshot is in the store [stores] names for its type, else in [default].
 */
@InternalWowApi
class SnapshotStoreRouter(
    private val default: SnapshotStore,
    private val stores: Map<AggregateType, SnapshotStore>,
) : SnapshotStore {
    /** The store of [type]'s snapshots, behind its own routing. */
    private fun storeOf(type: AggregateType): SnapshotStore = stores[type] ?: default

    override suspend fun save(snapshot: SnapshotRecord): Boolean = storeOf(snapshot.ref.type).save(snapshot)

    /** Each store's snapshots with one call of its own [SnapshotStore.saveAll]; the outcomes in the order given. */
    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
        val outcomes = arrayOfNulls<SnapshotSaveOutcome>(snapshots.size)
        snapshots.indices.groupBy { storeOf(snapshots[it].ref.type) }.forEach { (store, indexes) ->
            store.saveAll(indexes.map(snapshots::get)).forEachIndexed { at, outcome -> outcomes[indexes[at]] = outcome }
        }
        return outcomes.map { checkNotNull(it) }
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = storeOf(ref.type).load(ref)

    /**
     * The rebuild control plane of each type's store: a router over the stores that have one, null when none has. A
     * type whose store has none is refused, as the node's maintenance never asks it (it rebuilds per named store).
     */
    override val rebuilds: SnapshotRebuildStore? =
        if ((stores.values + default).none { it.rebuilds != null }) null else Rebuilds()

    private inner class Rebuilds : SnapshotRebuildStore {
        private fun of(type: AggregateType): SnapshotRebuildStore =
            requireNotNull(storeOf(type).rebuilds) { "The snapshot store of $type cannot rebuild its snapshots." }

        override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = of(snapshot.ref.type).rewrite(snapshot)

        override suspend fun markStale(ref: AggregateRef): Boolean = of(ref.type).markStale(ref)

        override suspend fun pending(
            type: AggregateType,
            revision: Int,
            after: AggregateRef?,
            limit: Int,
        ): List<AggregateRef> = of(type).pending(type, revision, after, limit)

        override suspend fun table(type: AggregateType): SnapshotTable? = of(type).table(type)

        override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable =
            of(type).claim(type, revision, lower)

        override suspend fun mark(
            type: AggregateType,
            revision: Int,
            rebuilding: Boolean?,
            backfilled: Boolean?,
        ): SnapshotTable? = of(type).mark(type, revision, rebuilding, backfilled)
    }

    @InternalWowApi
    companion object {
        /** [default] alone when no type keeps its snapshots elsewhere, else the router over them. */
        @JvmStatic
        fun of(default: SnapshotStore, stores: Map<AggregateType, SnapshotStore>): SnapshotStore {
            val elsewhere = stores.filterValues { it !== default }
            return if (elsewhere.isEmpty()) default else SnapshotStoreRouter(default, elsewhere)
        }
    }
}
