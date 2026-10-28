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

package me.ahoo.wow.spi.log

import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.snapshot.SnapshotStore

/**
 * An optional capability of a store that holds a context's log and its snapshots in one database: reading an
 * aggregate's snapshot and the tail after it in one round trip ([me.ahoo.wow.spi.store.Store.snapshotTails]). The
 * runtime gives it to the kernel for the aggregate types whose log and primary snapshots are on that one store; the
 * others are read the way every log can: the snapshot, then the tail ([readSnapshotTailApart]).
 *
 * The kernel reads through it on a cache miss, and reads apart, warning once and counting the fallback (the observer's
 * [me.ahoo.wow.spi.observation.Observer.storeFellBack]), when it fails.
 */
@WowSpi
fun interface SnapshotTailReader {
    /**
     * [ref]'s snapshot and the tail after it with the request keys before it, as [EventLog.readTail] reads them from
     * the snapshot's version, as one statement sees them; with no snapshot, every stream of [ref] and no earlier
     * keys. A failure is thrown.
     */
    suspend fun readWithSnapshot(ref: AggregateRef, earlier: Int): SnapshotTail
}

/**
 * [ref]'s snapshot in [snapshots] and the tail after it, read apart: [SnapshotStore.load], then [EventLog.readTail]
 * from the snapshot's version, or every stream of [ref] (a replay) and no earlier keys when there is no snapshot.
 */
@InternalWowApi
suspend fun EventLog.readSnapshotTailApart(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail {
    require(earlier >= 0) { "At most a non-negative number of earlier requests." }
    val snapshot = snapshots.load(ref) ?: return SnapshotTail(null, AggregateTail(read(ref).toList(), emptyList()))
    return SnapshotTail(snapshot, readTail(ref, snapshot.version, earlier))
}
