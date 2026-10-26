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

package me.ahoo.wow.kernel.memory

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import java.util.concurrent.ConcurrentHashMap

/**
 * A [SnapshotStore] in memory, for tests and samples. The version guard is one atomic compute per ref, and a table's
 * claim one atomic compute per aggregate type.
 */
@InternalWowApi
class InMemorySnapshotStore : SnapshotStore, SnapshotRebuildStore {
    /** This store rewrites its snapshots in place: it is its own rebuild control plane. */
    override val rebuilds: SnapshotRebuildStore get() = this

    private val snapshots = ConcurrentHashMap<AggregateRef, Stored>()
    private val tables = ConcurrentHashMap<AggregateType, SnapshotTable>()

    /** A stored snapshot, and the version the log holds that it does not fold when it is marked stale. */
    private data class Stored(val snapshot: SnapshotRecord, val staleVersion: Int? = null)

    override suspend fun save(snapshot: SnapshotRecord): Boolean {
        var written = false
        snapshots.compute(snapshot.ref) { _, stored ->
            when {
                stored == null -> Stored(snapshot).also { written = true }
                snapshot.version <= stored.snapshot.version -> stored
                snapshot.stateRevision >= stored.snapshot.stateRevision ->
                    Stored(snapshot, stored.staleVersion?.takeIf { it > snapshot.version }).also { written = true }

                else -> stored.copy(staleVersion = maxOf(stored.staleVersion ?: 0, snapshot.version))
            }
        }
        return written
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = snapshots[ref]?.snapshot

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean {
        var written = false
        snapshots.compute(snapshot.ref) { _, stored ->
            if (stored == null || snapshot.version >= stored.snapshot.version) {
                written = true
                Stored(snapshot, stored?.staleVersion?.takeIf { it > snapshot.version })
            } else {
                stored
            }
        }
        return written
    }

    override suspend fun markStale(ref: AggregateRef): Boolean {
        var marked = false
        snapshots.computeIfPresent(ref) { _, stored ->
            marked = true
            stored.copy(staleVersion = maxOf(stored.staleVersion ?: 0, stored.snapshot.version))
        }
        return marked
    }

    override suspend fun pending(
        type: AggregateType,
        revision: Int,
        after: AggregateRef?,
        limit: Int,
    ): List<AggregateRef> {
        require(limit >= 1) { "A page holds at least one ref." }
        return snapshots.values.asSequence()
            .filter { it.snapshot.ref.type == type }
            .filter { it.snapshot.stateRevision != revision || it.staleVersion != null }
            .map { it.snapshot.ref }
            .filter { after == null || KEY_ORDER.compare(it, after) > 0 }
            .sortedWith(KEY_ORDER)
            .take(limit)
            .toList()
    }

    override suspend fun table(type: AggregateType): SnapshotTable? = tables[type]

    override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable =
        tables.compute(type) { _, table ->
            when {
                table == null -> SnapshotTable(revision, rebuilding = revision != 1, backfilled = false)
                revision > table.revision || revision < table.revision && lower ->
                    table.copyWith(revision = revision, rebuilding = true)

                else -> table
            }
        }!!

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? = tables.computeIfPresent(type) { _, table ->
        if (table.revision != revision) {
            table
        } else {
            table.copyWith(rebuilding = rebuilding ?: table.rebuilding, backfilled = backfilled ?: table.backfilled)
        }
    }?.takeIf { it.revision == revision }

    private companion object {
        /** By tenant, then aggregate id, each by code point, as a database's binary collation orders them. */
        val KEY_ORDER: Comparator<AggregateRef> = Comparator { left, right ->
            codePoints(left.tenantId, right.tenantId).takeIf { it != 0 } ?: codePoints(left.id, right.id)
        }

        fun codePoints(left: String, right: String): Int {
            var i = 0
            var j = 0
            while (i < left.length && j < right.length) {
                val a = left.codePointAt(i)
                val b = right.codePointAt(j)
                if (a != b) {
                    return a.compareTo(b)
                }
                i += Character.charCount(a)
                j += Character.charCount(b)
            }
            return (left.length - i).compareTo(right.length - j)
        }
    }
}
