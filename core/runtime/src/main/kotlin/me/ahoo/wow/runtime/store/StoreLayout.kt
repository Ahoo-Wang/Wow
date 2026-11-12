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

package me.ahoo.wow.runtime.store

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.node.PlacedStores
import me.ahoo.wow.runtime.node.SnapshotTailReaders
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.store.CoveredSnapshots
import me.ahoo.wow.spi.store.Store

/**
 * Where each aggregate's log and snapshots are: its log on the store [log] names (`wow.log.store`) unless the
 * logs map names another for its type (`wow.aggregates.[<context>.<aggregate>].log.store`); its snapshots on one store,
 * the [snapshot] store (`wow.snapshot.store`, the [log] store by default) unless the snapshots map names another for its
 * type (`.snapshot.store`); and, when its queries read another store (`.query.snapshot.store`), a copy of them there,
 * which its own subscription writes. The [snapshot] store is each context's default one, whose subscription is
 * `wow.snapshot`.
 */
@InternalWowApi
class StoreLayout(
    val log: String,
    val snapshot: String = log,
    private val logs: Map<AggregateType, String> = emptyMap(),
    private val snapshots: Map<AggregateType, String> = emptyMap(),
    private val copies: Map<AggregateType, String> = emptyMap(),
) {
    /** The store of [type]'s log: its log group. */
    fun logStoreOf(type: AggregateType): String = logs[type] ?: log

    /** Where [type]'s snapshots are written: its own store, and its copy when its queries read another. */
    fun snapshotPlacementOf(type: AggregateType): SnapshotPlacement =
        SnapshotPlacement(snapshotStoreOf(type), copies[type])

    /** The store of [type]'s own snapshots, which its commands load from and its state reads read. */
    fun snapshotStoreOf(type: AggregateType): String = snapshots[type] ?: snapshot

    /** The store [type]'s snapshot queries read: its copy's when it has one, else its own. */
    fun querySnapshotStoreOf(type: AggregateType): String = snapshotPlacementOf(type).query

    /** The stores some log is on. */
    val logStores: Set<String> get() = setOf(log) + logs.values

    /** The stores some snapshots are on, own or copied. */
    val snapshotStores: Set<String> get() = setOf(snapshot) + snapshots.values + copies.values

    /**
     * The stores of [context] among [stores], each opened as this layout says: its default log group on [log], with
     * the checkpoints, leases, failure ledger, reservations and timers of that store; a log group per other store its
     * aggregates' logs are on, with that store's reservations and timers; its snapshot targets, each type's own store
     * and copy.
     */
    fun open(context: String, stores: Map<String, Store>, partitions: Int): ContextBackends {
        val default = stores.getValue(log)
        val groups = logs.filter { (type, store) -> type.context == context && store != log }.entries
            .groupBy({ it.value }, { it.key })
            .map { (name, types) ->
                val store = stores.getValue(name)
                LogGroupBackends(
                    name,
                    types.toSet(),
                    store.log(context, partitions),
                    store.checkpoints,
                    store.ledger,
                    store.timers(context, partitions),
                    store.reservations,
                )
            }
        val placed = (snapshots.keys + copies.keys).filter { it.context == context }.associateWith(
            ::snapshotPlacementOf
        )
        val names = LinkedHashSet<String>().apply {
            add(snapshot)
            placed.values.forEach { addAll(it.targets) }
        }
        val targets = SnapshotTargets(snapshot, names.associateWith { stores.getValue(it).snapshots(context) }, placed)
        // A context on one store alone, its log, the checkpoints its snapshot subscription saves and every snapshot
        // target: those checkpoints cover its snapshot writes there (CoveredSnapshots). Split, none is covered.
        if (groups.isEmpty() && names == setOf(log)) default.facet(CoveredSnapshots::class.java)?.cover(context)
        val backends = ContextBackends(
            log = default.log(context, partitions),
            checkpoints = default.checkpoints,
            leases = default.leases,
            ledger = default.ledger,
            snapshots = targets,
            reservations = default.reservations,
            timers = default.timers(context, partitions),
            store = log,
            groups = groups,
        )
        val logStores = setOf(log) + groups.map { it.store }
        val own = setOf(snapshot) + placed.values.map { it.store }
        val writePath = (logStores + own).mapTo(LinkedHashSet(), stores::getValue)
        return backends.copyWith(
            stores = PlacedStores(writePath, names.map(stores::getValue).toSet() - writePath),
            snapshotTails = snapshotTailsOf(context, stores),
        )
    }

    /**
     * The one-round-trip reads of [context]'s snapshots with their tails: of each aggregate type whose log store holds
     * its own snapshots, that store's ([Store.snapshotTails]).
     */
    private fun snapshotTailsOf(context: String, stores: Map<String, Store>): SnapshotTailReaders {
        fun readerOf(logStore: String, own: String): SnapshotTailReader? =
            if (logStore == own) stores.getValue(logStore).snapshotTails(context) else null
        val types = (logs.keys + snapshots.keys).filter { it.context == context }
        return SnapshotTailReaders(
            readerOf(log, snapshot),
            types.associateWith { readerOf(logStoreOf(it), snapshotStoreOf(it)) },
        )
    }

    @InternalWowApi
    companion object {
        /**
         * The layout [placements] configure over [stores], the node's store instances by name, for the aggregate
         * types the node [serves]. Refused: a default or per-type store that names no store, or one that holds no log
         * or no snapshots ([Store.holdsLogs], [Store.holdsSnapshots]); a `wow.aggregates` key of a type the node does not serve; more than one store
         * and no `wow.log.store`; two log stores on one database.
         */
        fun of(
            placements: StorePlacements,
            stores: Map<String, Store>,
            serves: Collection<AggregateType>,
        ): StoreLayout {
            require(stores.isNotEmpty()) { "The node has no store." }
            val log = defaultLogStore(placements, stores)
            val snapshot = placements.snapshot?.takeIf { it.isNotBlank() } ?: log
            stores.checked("wow.log.store", log, Store::holdsLogs, "log")
            stores.checked("wow.snapshot.store", snapshot, Store::holdsSnapshots, "snapshots")
            val served = serves.associateBy { "${it.context}.${it.name}" }
            val logs = LinkedHashMap<AggregateType, String>()
            val snapshots = LinkedHashMap<AggregateType, String>()
            val copies = LinkedHashMap<AggregateType, String>()
            placements.aggregates.forEach { (key, aggregate) ->
                val logStore = aggregate.log?.takeIf { it.isNotBlank() }
                val snapshotStore = aggregate.snapshot?.takeIf { it.isNotBlank() }
                val queryStore = aggregate.querySnapshot?.takeIf { it.isNotBlank() }
                if (logStore == null && snapshotStore == null && queryStore == null) {
                    return@forEach
                }
                val type = configNotNull(
                    served[key],
                    "wow.aggregates.[$key]",
                    "Name an aggregate type the node serves."
                ) {
                    "The store placement of $key names no aggregate type this node serves; it serves ${served.keys}."
                }
                logStore?.let { logs[type] = stores.checked("wow.aggregates.[$key].log.store", it, Store::holdsLogs, "log") }
                snapshotStore?.let {
                    val snapshotKey = "wow.aggregates.[$key].snapshot.store"
                    snapshots[type] = stores.checked(snapshotKey, it, Store::holdsSnapshots, "snapshots")
                }
                val own = snapshotStore ?: snapshot
                queryStore?.takeIf { it != own }?.let {
                    val queryKey = "wow.aggregates.[$key].query.snapshot.store"
                    copies[type] = stores.checked(queryKey, it, Store::holdsSnapshots, "snapshots")
                }
            }
            return StoreLayout(log, snapshot, logs, snapshots, copies).also { layout ->
                layout.logStores.map(stores::getValue).groupBy { it.location }.values.filter { it.size > 1 }.forEach {
                    throw IllegalStateException(
                        "The log stores ${it.map(Store::name)} are on one database: each log group's partition " +
                            "sequences need a database of their own.",
                    )
                }
            }
        }

        /** The store the logs are on by default: the one named for them, else the node's only store. */
        private fun defaultLogStore(placements: StorePlacements, stores: Map<String, Store>): String =
            placements.log?.takeIf { it.isNotBlank() } ?: stores.keys.singleOrNull()
                ?: throw WowConfigurationException(
                    "wow.log.store",
                    null,
                    "The node has several stores, ${stores.keys}, and none is named for its logs.",
                    "Name the store the logs are on by default.",
                )

        /** [name], once [key] is found to name one of these stores, which [holds] [what]. */
        private fun Map<String, Store>.checked(
            key: String,
            name: String,
            holds: (Store) -> Boolean,
            what: String
        ): String {
            val store = configNotNull(this[name], key, "Name one of the node's stores: $keys.", name) {
                "The store placement names the store $name, which is none of the node's stores."
            }
            configCheck(holds(store), key, name, "Name a store that holds $what.") {
                "The store placement names the store $name, of type ${store.type}, which holds no $what."
            }
            return name
        }
    }
}

/**
 * Where a host's configuration places the logs and snapshots, as the `wow.*` keys say: the default [log] store
 * (`wow.log.store`), the default [snapshot] store (`wow.snapshot.store`), and each aggregate's own, by
 * `<context>.<aggregate>` ([aggregates], `wow.aggregates.[<context>.<aggregate>]`).
 */
@InternalWowApi
class StorePlacements(
    val log: String? = null,
    val snapshot: String? = null,
    val aggregates: Map<String, AggregatePlacement> = emptyMap(),
)

/**
 * One aggregate type's stores: its [log] store (`.log.store`), its [snapshot] store (`.snapshot.store`), and the store
 * its snapshot queries read, [querySnapshot] (`.query.snapshot.store`), which keeps a copy of them when it is another;
 * none set, the defaults.
 */
@InternalWowApi
class AggregatePlacement(
    val log: String? = null,
    val snapshot: String? = null,
    val querySnapshot: String? = null,
)
