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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore

/**
 * The stores of one bounded context on a node (a log, its partitions and leases are per context). The
 * [log]'s partition count is the context's. Without [snapshots] the context has no snapshot subscription, and its
 * default wait is COMMITTED; without [reservations] its commands cannot reserve keys; without [timers] its commands and
 * sagas cannot schedule commands: a schedule is committed but never sent.
 *
 * **Log groups**: the aggregates whose log is on one store instance form a group, with its own
 * partition sequences and aggregate tables, and the checkpoints, parked entries and timers of the subscriptions that
 * read it in the same store. [log], [checkpoints], [ledger] and [timers] are the context's default group, on the store
 * named [store]: every aggregate type of the context that no other group names. Each of [groups] is another store's,
 * with the aggregate types it holds. Partitions and leases stay per context: every group has the context's partitions,
 * and the owner of a partition reads it in every group. One store, no [groups]: one group, the whole context in it.
 *
 * Fields may be added: construct it with [of]; the constructor, which also takes the log groups and
 * snapshot targets the host configures, is internal to Wow.
 */
class ContextBackends @InternalWowApi constructor(
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val leases: LeaseStore,
    val ledger: FailureLedger,
    /**
     * The context's snapshot stores: every store its aggregate types' snapshots are written to, with
     * each type's primary one; [SnapshotTargets.single] for one store. None, its snapshots are not kept. The host's
     * wiring, internal to Wow: an application reads [primarySnapshots].
     */
    @InternalWowApi
    val snapshots: SnapshotTargets? = null,
    val reservations: ReservationStore? = null,
    val timers: TimerStore? = null,
    /** The store instance of the default log group, as the host names it (`wow.log.store`). */
    val store: String = DEFAULT_STORE,
    /** The context's other log groups, each on a store of its own. */
    val groups: List<LogGroupBackends> = emptyList(),
    /**
     * The store instances these ports were opened on, which the node creates the schemas of when it starts and probes
     * for its health; none for backends an application opened itself. Whoever opened them closes them: the host, once
     * the node and everything else that reads them stopped.
     */
    val stores: PlacedStores = PlacedStores.NONE,
    /**
     * The one-round-trip reads of a snapshot with its tail, by aggregate type: one for each type whose log and
     * primary snapshots are on one store that reads them together ([Store.snapshotTails]).
     */
    val snapshotTails: SnapshotTailReaders = SnapshotTailReaders.NONE,
) {
    /** The snapshots the write side and the state readers read: each type's primary target. */
    val primarySnapshots: SnapshotStore? get() = snapshots?.primary

    init {
        require(store.isNotBlank()) { "A log group names its store." }
        val stores = groups.map { it.store }
        require(store !in stores && stores.toSet().size == stores.size) {
            "Each log group is on a store of its own: $store and $stores."
        }
        val types = groups.flatMap { it.types }
        require(types.toSet().size == types.size) { "An aggregate type is in one log group: $types." }
        groups.forEach { group ->
            require(group.log.partitions == log.partitions) {
                "The log group ${group.store} has ${group.log.partitions} partitions, not the context's ${log.partitions}."
            }
            require((group.timers == null) == (timers == null)) {
                "Either every log group has a timer store or none has: ${group.store} differs from $store."
            }
        }
    }

    /** These ContextBackends with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        log: EventLog = this.log,
        checkpoints: CheckpointStore = this.checkpoints,
        leases: LeaseStore = this.leases,
        ledger: FailureLedger = this.ledger,
        snapshots: SnapshotTargets? = this.snapshots,
        reservations: ReservationStore? = this.reservations,
        timers: TimerStore? = this.timers,
        store: String = this.store,
        groups: List<LogGroupBackends> = this.groups,
        stores: PlacedStores = this.stores,
        snapshotTails: SnapshotTailReaders = this.snapshotTails,
    ): ContextBackends = ContextBackends(
        log,
        checkpoints,
        leases,
        ledger,
        snapshots,
        reservations,
        timers,
        store,
        groups,
        stores,
        snapshotTails,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ContextBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ContextBackends(log=$log, checkpoints=$checkpoints, leases=$leases, ledger=$ledger, snapshots=$snapshots, " +
            "reservations=$reservations, timers=$timers, store=$store, groups=$groups, stores=$stores)"

    private fun valueFields(): List<Any?> =
        listOf(log, checkpoints, leases, ledger, snapshots, reservations, timers, store, groups, stores, snapshotTails)

    companion object {
        /** The name of the store of a context's default log group when the host names none. */
        const val DEFAULT_STORE: String = "default"

        /**
         * The stores of a context with one log group, on the store [DEFAULT_STORE]: its [log], [checkpoints],
         * [leases] and failure [ledger], and optionally its one snapshot store [snapshots] (as
         * [SnapshotTargets.single]), [reservations] and [timers].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            log: EventLog,
            checkpoints: CheckpointStore,
            leases: LeaseStore,
            ledger: FailureLedger,
            snapshots: SnapshotStore? = null,
            reservations: ReservationStore? = null,
            timers: TimerStore? = null,
        ): ContextBackends = ContextBackends(
            log,
            checkpoints,
            leases,
            ledger,
            snapshots?.let { SnapshotTargets.single(it) },
            reservations,
            timers,
        )
    }
}

/**
 * The store instances a context's ports were opened on: those its [writePath] needs (each store holding one of its
 * logs, each type's own snapshot store), whose outage makes the node not ready, and the [others] (the stores of the
 * types' snapshot copies, which their queries read), whose outage the node's health shows without taking it out of
 * service.
 */
class PlacedStores @InternalWowApi constructor(
    val writePath: Set<Store>,
    internal val others: Set<Store> = emptySet()
) {
    /** Every placed store, the write path's first. */
    val all: Set<Store> get() = writePath + others

    override fun equals(other: Any?): Boolean =
        this === other || other is PlacedStores && writePath == other.writePath && others == other.others

    override fun hashCode(): Int = 31 * writePath.hashCode() + others.hashCode()

    override fun toString(): String = "PlacedStores(writePath=$writePath, others=$others)"

    @InternalWowApi
    companion object {
        /** No store: backends an application opened itself. */
        @JvmField
        val NONE: PlacedStores = PlacedStores(emptySet())
    }
}

/**
 * The one-round-trip reads of a context's snapshots with their tails ([Store.snapshotTails]): the [default] one, of the
 * aggregate types the layout places nowhere in particular, when their log store holds their primary snapshots, and
 * the one of each type [placed] elsewhere, null when its log and primary snapshots are on different stores.
 */
class SnapshotTailReaders @InternalWowApi constructor(
    private val default: SnapshotTailReader?,
    private val placed: Map<AggregateType, SnapshotTailReader?> = emptyMap(),
) {
    /** The reader of [type]'s snapshots with their tails; null when they are read apart. */
    fun of(type: AggregateType): SnapshotTailReader? = if (type in placed) placed[type] else default

    /** These readers, each wrapped by [wrap]. */
    @InternalWowApi
    fun map(wrap: (AggregateType?, SnapshotTailReader) -> SnapshotTailReader): SnapshotTailReaders =
        SnapshotTailReaders(
            default?.let { wrap(null, it) },
            placed.mapValues { (type, it) -> it?.let { wrap(type, it) } }
        )

    override fun toString(): String = "SnapshotTailReaders(default=${default != null}, placed=${placed.keys})"

    @InternalWowApi
    companion object {
        /** No reader: every snapshot is read apart from its tail. */
        @JvmField
        val NONE: SnapshotTailReaders = SnapshotTailReaders(null)
    }
}

/**
 * One log group of a context other than its default one: the aggregate [types] whose log is [log], on
 * the store instance [store]; the checkpoints, parked entries and timers of the subscriptions reading it are in
 * [checkpoints], [ledger] and [timers], on the same store, and so are its types' [reservations] (an
 * aggregate type's reservations follow its log; a group without its own uses the context's).
 *
 * Fields may be added: Wow builds the groups from the host's configuration, so the constructor is
 * internal to Wow.
 */
class LogGroupBackends @InternalWowApi constructor(
    val store: String,
    val types: Set<AggregateType>,
    val log: EventLog,
    val checkpoints: CheckpointStore,
    val ledger: FailureLedger,
    val timers: TimerStore? = null,
    val reservations: ReservationStore? = null,
) {
    init {
        require(store.isNotBlank()) { "A log group names its store." }
        require(types.isNotEmpty()) { "A log group holds at least one aggregate type." }
    }

    /** This LogGroupBackends with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        store: String = this.store,
        types: Set<AggregateType> = this.types,
        log: EventLog = this.log,
        checkpoints: CheckpointStore = this.checkpoints,
        ledger: FailureLedger = this.ledger,
        timers: TimerStore? = this.timers,
        reservations: ReservationStore? = this.reservations,
    ): LogGroupBackends = LogGroupBackends(store, types, log, checkpoints, ledger, timers, reservations)

    override fun equals(other: Any?): Boolean =
        this === other || other is LogGroupBackends && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "LogGroupBackends(store=$store, types=$types, log=$log, checkpoints=$checkpoints, ledger=$ledger, " +
            "timers=$timers, reservations=$reservations)"

    private fun valueFields(): List<Any?> = listOf(store, types, log, checkpoints, ledger, timers, reservations)
}

/** Opens the [ContextBackends] of each bounded context the node hosts; the host closes what it opened. */
fun interface BackendFactory {
    fun backends(context: String): ContextBackends
}
