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

package me.ahoo.wow.consumer

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import java.util.concurrent.ConcurrentHashMap

/**
 * The provider of the `acme-snapshots` stores: a third-party backend, written as one outside Wow would write it, on
 * the public store SPI and query facet alone, in a module that opts in to nothing. Found by `ServiceLoader`.
 *
 * Its stores hold snapshots only: the log SPI's public closure is checked by the ABI-closure rule, not here, so
 * StoreTck's log specs skip for it by design.
 */
class AcmeSnapshotsProvider : StoreProvider {
    override val type: String = TYPE

    override fun open(config: StoreConfig): Store = AcmeSnapshotsStore(config)

    companion object {
        const val TYPE: String = "acme-snapshots"
    }
}

/**
 * An `acme-snapshots` store: each context's snapshots in memory, standing in for a database ([AcmeSnapshotStore]),
 * and its queries as its facet ([StoreQueries]), over the in-memory reference query storage every snapshot it writes
 * is copied to. It holds no log, so it refuses the log and the control plane, naming itself.
 */
class AcmeSnapshotsStore(config: StoreConfig) : Store(config) {
    private val queryStorage = InMemoryQueryStorage()
    private val queries = AcmeQueries(queryStorage)

    override val location: Any get() = this

    override val holdsLogs: Boolean get() = false

    override fun <F : Any> facet(type: Class<F>): F? = when (type) {
        StoreQueries::class.java -> type.cast(queries)
        else -> null
    }

    override fun openSnapshots(context: String): Opened<SnapshotStore> = Opened.of(AcmeSnapshotStore(queryStorage))

    override fun openCheckpoints(): Opened<CheckpointStore> = throw noLog()

    override fun openLeases(): Opened<LeaseStore> = throw noLog()

    override fun openLedger(): Opened<FailureLedger> = throw noLog()

    override fun openReservations(): Opened<ReservationStore> = throw noLog()

    override fun openLog(context: String, partitions: Int): Opened<EventLog> = throw noLog()

    override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = throw noLog()

    private fun noLog(): UnsupportedOperationException =
        UnsupportedOperationException("The acme-snapshots store $name holds no log: snapshots only.")
}

/**
 * The snapshots of one context: the version guard of [SnapshotStore.save] as one atomic compute per ref, each snapshot
 * written copied to [queries]. It has no rebuild control plane: a snapshot of a lower state revision is left as it is.
 */
class AcmeSnapshotStore(private val queries: InMemoryQueryStorage) : SnapshotStore {
    private val snapshots = ConcurrentHashMap<AggregateRef, SnapshotRecord>()

    override suspend fun save(snapshot: SnapshotRecord): Boolean {
        var written = false
        snapshots.compute(snapshot.ref) { _, stored ->
            val replaces = stored == null ||
                snapshot.version > stored.version && snapshot.stateRevision >= stored.stateRevision
            if (replaces) {
                written = true
                snapshot
            } else {
                stored
            }
        }
        if (written) {
            queries.save(snapshot)
        }
        return written
    }

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = snapshots[ref]
}

/** The queries of an `acme-snapshots` store: its snapshots, over the reference query storage; no `events`. */
class AcmeQueries(private val storage: InMemoryQueryStorage) : StoreQueries {
    override fun snapshots(options: StoreQueryOptions): SnapshotQueries = object : SnapshotQueries {
        override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend> = storage.binding(key)
    }
}
