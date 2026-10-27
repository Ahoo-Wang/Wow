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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One store instance of the node, `wow.stores.<name>`, as its [StoreProvider] opened it: it opens each
 * context's log, snapshots and timers on their first use, and the checkpoints, leases, failure ledger and reservations
 * the contexts whose stores it holds share, and remembers what creates their schemas ([createSchemas]).
 *
 * A backend subclasses it and opens its ports in the `open*` functions; the node calls them once each (a context's
 * log once per context), so a port is never opened twice.
 */
@WowSpi
abstract class Store(val config: StoreConfig) {
    private val schemas = CopyOnWriteArrayList<() -> Unit>()
    private val logs = ConcurrentHashMap<String, EventLog>()
    private val snapshotStores = ConcurrentHashMap<String, SnapshotStore>()
    private val timerStores = ConcurrentHashMap<String, TimerStore>()

    /** The store's name, `wow.stores.<name>`. */
    val name: String get() = config.name

    /** The type of its provider, [StoreProvider.type]. */
    val type: String get() = config.type

    /**
     * The database the store writes in, as two stores would share it (a `DataSource`, a client and a database name):
     * two stores whose logs share one would share their partition sequences, so the node refuses them.
     */
    abstract val location: Any

    /**
     * Whether the store outlives the node and is shared by the nodes of the service: a store in memory is not, so a
     * node with a log on it serves one node alone and routes its commands locally.
     */
    open val durable: Boolean get() = true

    /** Whether the store holds logs (with their checkpoints, leases, timers and ledger). */
    open val holdsLogs: Boolean get() = true

    /** Whether the store holds snapshots. */
    open val holdsSnapshots: Boolean get() = true

    /**
     * Whether the store may be an aggregate type's primary snapshot store, the one its commands load their state from
     * and its state reads read: one that holds snapshots, unless it only holds a copy beside a primary, as a search
     * index read in near real time does.
     */
    open val primarySnapshots: Boolean get() = holdsSnapshots

    /**
     * How many callers wait now for a connection of the pool the store's appends draw from (the fast
     * half of its write pressure), as the pool says it (HikariCP's threads awaiting a connection, the MongoDB driver's
     * pending check-outs); null when the store's pool does not say. Called about once a second; it does no I/O.
     */
    open fun waitingForConnection(): Int? = null

    val checkpoints: CheckpointStore by lazy { openCheckpoints() }
    val leases: LeaseStore by lazy { openLeases() }
    val ledger: FailureLedger by lazy { openLedger() }
    val reservations: ReservationStore by lazy { openReservations() }

    /** [context]'s log on this store, of [partitions] partitions. */
    fun log(context: String, partitions: Int): EventLog = logs.computeIfAbsent(context) { openLog(it, partitions) }

    /** [context]'s log on this store, when it was opened. */
    fun openedLog(context: String): EventLog? = logs[context]

    /** [context]'s snapshots on this store. */
    fun snapshots(context: String): SnapshotStore = snapshotStores.computeIfAbsent(context, ::openSnapshots)

    /**
     * [context]'s timers on this store, of a log of [partitions] partitions: a timer's partition is its owner's
     * ([TimerStore]).
     */
    fun timers(context: String, partitions: Int): TimerStore =
        timerStores.computeIfAbsent(context) { openTimers(it, partitions) }

    /** Creates the schemas of what the store opened so far, where they are missing. */
    fun createSchemas() {
        schemas.forEach { it() }
    }

    /** Remembers that [create] creates the schema of what was just opened: [createSchemas] runs it. */
    protected fun schema(create: () -> Unit) {
        schemas += create
    }

    protected abstract fun openCheckpoints(): CheckpointStore

    protected abstract fun openLeases(): LeaseStore

    protected abstract fun openLedger(): FailureLedger

    protected abstract fun openReservations(): ReservationStore

    protected abstract fun openLog(context: String, partitions: Int): EventLog

    protected abstract fun openSnapshots(context: String): SnapshotStore

    protected abstract fun openTimers(context: String, partitions: Int): TimerStore

    override fun toString(): String = "$name ($type)"
}
