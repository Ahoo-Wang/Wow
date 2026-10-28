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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One store instance of the node, `wow.stores.<name>`, as its [StoreProvider] opened it: it opens each
 * context's log, snapshots and timers on their first use, and the checkpoints, leases, failure ledger and reservations
 * the contexts whose stores it holds share, and remembers what creates their schemas ([createSchemas]).
 *
 * A backend subclasses it and opens its ports in the `open*` functions, each returning the port [Opened] with what
 * creates its schema, so that no port is opened without it; the node calls them once each (a context's log once per
 * context), so a port is never opened twice, and runs [createSchemas] when it starts.
 */
@WowSpi
abstract class Store(val config: StoreConfig) : AutoCloseable {
    private val schemas = CopyOnWriteArrayList<() -> Unit>()
    private val tails = ConcurrentHashMap<String, Optional<SnapshotTailReader>>()
    private val closed = AtomicBoolean()

    /** How many of [schemas] [createSchemas] ran. */
    private var created = 0
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
     * The facet of [type] the store has, null when it has none: a capability a backend opts into beyond the ports, read
     * by the module that knows the facet's type. The documented facets are [StorePool], the connections the store
     * draws from, and the query side `me.ahoo.wow.query.store.StoreQueries` (`:query`), the snapshot and `events`
     * queries over what the store holds. A facet is a capability, never a way around a port: what a port does, its
     * facet does not do again.
     */
    open fun <F : Any> facet(type: Class<F>): F? = null

    val checkpoints: CheckpointStore by lazy { recorded(openCheckpoints()) }
    val leases: LeaseStore by lazy { recorded(openLeases()) }
    val ledger: FailureLedger by lazy { recorded(openLedger()) }
    val reservations: ReservationStore by lazy { recorded(openReservations()) }

    /** [context]'s log on this store, of [partitions] partitions. */
    fun log(context: String, partitions: Int): EventLog =
        logs.computeIfAbsent(context) { recorded(openLog(it, partitions)) }

    /** [context]'s log on this store, when it was opened. */
    fun openedLog(context: String): EventLog? = logs[context]

    /** [context]'s snapshots on this store. */
    fun snapshots(context: String): SnapshotStore =
        snapshotStores.computeIfAbsent(context) { recorded(openSnapshots(it)) }

    /**
     * [context]'s timers on this store, of a log of [partitions] partitions: a timer's partition is its owner's
     * ([TimerStore]).
     */
    fun timers(context: String, partitions: Int): TimerStore =
        timerStores.computeIfAbsent(context) { recorded(openTimers(it, partitions)) }

    /**
     * The one-round-trip read of [context]'s snapshots with their log's tail, when this store holds both in one
     * database and can read them together; null otherwise. The runtime asks it once [context]'s log and snapshots on
     * this store are open, for the aggregate types whose log and primary snapshots are both here.
     */
    fun snapshotTails(context: String): SnapshotTailReader? {
        check(logs.containsKey(context) && snapshotStores.containsKey(context)) {
            "$this reads $context's snapshots with their tails once its log and snapshots there are open."
        }
        return tails.computeIfAbsent(context) { Optional.ofNullable(openSnapshotTails(it)) }.orElse(null)
    }

    /**
     * Checks that the store answers, as the node's health samples it: a cheap round trip to the database (a pooled
     * connection's validity, a ping), bounded by the caller; it throws when the store does not answer. Nothing by
     * default, for a store in memory.
     */
    open suspend fun probe() = Unit

    /**
     * Closes the store once the node that placed it stopped: what it holds of its own (a client it built, say), not
     * the resources the host gave it ([StoreConfig.resources]), which their owner closes. Closing it again does
     * nothing.
     */
    final override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeStore()
        }
    }

    /** What [close] releases, once: nothing by default. */
    protected open fun closeStore() = Unit

    /**
     * Creates the schemas of what the store opened so far, where they are missing: each port's once, so calling it
     * again only creates those of the ports opened since. The node calls it when it starts; a host may call it
     * earlier, before anything else reads the stores.
     */
    @Synchronized
    fun createSchemas() {
        while (created < schemas.size) {
            schemas[created]()
            created++
        }
    }

    /** [opened]'s port, remembering what creates its schema: [createSchemas] runs it. */
    private fun <P : Any> recorded(opened: Opened<P>): P {
        opened.schema?.let { schemas += it }
        return opened.port
    }

    protected abstract fun openCheckpoints(): Opened<CheckpointStore>

    protected abstract fun openLeases(): Opened<LeaseStore>

    protected abstract fun openLedger(): Opened<FailureLedger>

    protected abstract fun openReservations(): Opened<ReservationStore>

    protected abstract fun openLog(context: String, partitions: Int): Opened<EventLog>

    protected abstract fun openSnapshots(context: String): Opened<SnapshotStore>

    protected abstract fun openTimers(context: String, partitions: Int): Opened<TimerStore>

    /**
     * The one-round-trip read of [context]'s snapshots with its log's tail ([snapshotTails]), whose log and snapshots
     * this store opened: none by default.
     */
    protected open fun openSnapshotTails(context: String): SnapshotTailReader? = null

    override fun toString(): String = "$name ($type)"
}

/**
 * A port a [Store] opened, with what creates its schema where it is missing ([schema]; null when the port has none,
 * as a store in memory), which the store runs when the node starts ([Store.createSchemas]).
 */
@WowSpi
class Opened<out P : Any> @InternalWowApi constructor(val port: P, val schema: (() -> Unit)?) {
    override fun toString(): String = "Opened($port)"

    companion object {
        /** [port], whose schema [schema] creates. */
        @JvmStatic
        fun <P : Any> of(port: P, schema: () -> Unit): Opened<P> = Opened(port, schema)

        /** [port], which has no schema. */
        @JvmStatic
        fun <P : Any> of(port: P): Opened<P> = Opened(port, null)
    }
}

/** The facet of type [F] this store has ([Store.facet]), null when it has none. */
inline fun <reified F : Any> Store.facet(): F? = facet(F::class.java)
