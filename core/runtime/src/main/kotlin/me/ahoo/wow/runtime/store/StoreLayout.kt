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
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.store.Store

/**
 * Where each aggregate's log and snapshots are (decisions D172, D177): its log on the store [log] names
 * (`wow.log.store`) unless the logs map names another for its type (`wow.aggregates.[<context>.<aggregate>].log.store`);
 * its snapshots on the targets of its placement (`.snapshot.store`, `.snapshot.primary`), else of [snapshotDefault]
 * (`wow.snapshot.targets`, `wow.snapshot.primary`, the [snapshot] store alone by default). The [snapshot] store
 * (`wow.snapshot.store`) is each context's default one, whose subscription is `snapshot`.
 */
@InternalWowApi
class StoreLayout(
    val log: String,
    val snapshot: String = log,
    private val logs: Map<AggregateType, String> = emptyMap(),
    snapshots: Map<AggregateType, String> = emptyMap(),
    private val placements: Map<AggregateType, SnapshotPlacement> =
        snapshots.mapValues { SnapshotPlacement(listOf(it.value)) },
    val snapshotDefault: SnapshotPlacement = SnapshotPlacement(listOf(snapshot)),
) {
    /** The store of [type]'s log: its log group. */
    fun logStoreOf(type: AggregateType): String = logs[type] ?: log

    /** Where [type]'s snapshots are written. */
    fun snapshotPlacementOf(type: AggregateType): SnapshotPlacement = placements[type] ?: snapshotDefault

    /** The store of [type]'s snapshots its snapshot queries read: its primary target. */
    fun snapshotStoreOf(type: AggregateType): String = snapshotPlacementOf(type).primary

    /** The stores some log is on. */
    val logStores: Set<String> get() = setOf(log) + logs.values

    /** The stores some snapshots are on. */
    val snapshotStores: Set<String>
        get() = setOf(snapshot) + snapshotDefault.targets + placements.values.flatMap { it.targets }

    /**
     * The stores of [context] among [stores], each opened as this layout says: its default log group on [log], with
     * the checkpoints, leases, failure ledger, reservations and timers of that store; a log group per other store its
     * aggregates' logs are on, with that store's reservations and timers (decision D307); its snapshot targets, each type's as its placement says.
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
                    store.timers(context),
                    store.reservations,
                )
            }
        val placed = placements.filterKeys { it.context == context }
        val names = LinkedHashSet<String>().apply {
            add(snapshot)
            addAll(snapshotDefault.targets)
            placed.values.forEach { addAll(it.targets) }
        }
        val targets = SnapshotTargets(
            snapshot,
            names.associateWith { stores.getValue(it).snapshots(context) },
            placed,
            snapshotDefault,
        )
        return ContextBackends(
            log = default.log(context, partitions),
            checkpoints = default.checkpoints,
            leases = default.leases,
            ledger = default.ledger,
            snapshots = targets.primary,
            reservations = default.reservations,
            timers = default.timers(context),
            store = log,
            groups = groups,
            snapshotTargets = targets,
        )
    }

    @InternalWowApi
    companion object {
        /**
         * The layout [placements] configure over [stores], the node's store instances by name, for the aggregate
         * types the node [serves]. Refused: a default or per-type store that names no store, or one that holds no log
         * or no snapshots ([Store.holdsLogs], [Store.holdsSnapshots]); a `wow.aggregates` key of a type the node does
         * not serve; more than one store and no `wow.log.store`; two log stores on one database.
         */
        fun of(
            placements: StorePlacements,
            stores: Map<String, Store>,
            serves: Collection<AggregateType>,
        ): StoreLayout {
            require(stores.isNotEmpty()) { "The node has no store." }
            val log = placements.log?.takeIf { it.isNotBlank() } ?: stores.keys.singleOrNull()
                ?: throw IllegalStateException(
                    "wow.stores names ${stores.keys}: set wow.log.store to the one the logs are on by default.",
                )
            val snapshot = placements.snapshot?.takeIf { it.isNotBlank() } ?: log
            stores.checked("wow.log.store", log, Store::holdsLogs, "log")
            stores.checked("wow.snapshot.store", snapshot, Store::holdsSnapshots, "snapshots")
            val fallback = stores.placement(
                "wow.snapshot",
                "targets",
                placements.snapshotTargets,
                placements.snapshotPrimary,
                listOf(snapshot),
            )
            val served = serves.associateBy { "${it.context}.${it.name}" }
            val logs = LinkedHashMap<AggregateType, String>()
            val snapshotPlacements = LinkedHashMap<AggregateType, SnapshotPlacement>()
            placements.aggregates.forEach { (key, aggregate) ->
                val logStore = aggregate.log?.takeIf { it.isNotBlank() }
                val placed = aggregate.snapshots.any { it.isNotBlank() } || !aggregate.snapshotPrimary.isNullOrBlank()
                if (logStore == null && !placed) {
                    return@forEach
                }
                val type = checkNotNull(served[key]) {
                    "wow.aggregates.[$key] names the stores of no aggregate type this node serves; it serves ${served.keys}."
                }
                logStore?.let { logs[type] = stores.checked("wow.aggregates.[$key].log.store", it, Store::holdsLogs, "log") }
                if (placed) {
                    snapshotPlacements[type] = stores.placement(
                        "wow.aggregates.[$key].snapshot",
                        "store",
                        aggregate.snapshots,
                        aggregate.snapshotPrimary,
                        fallback.targets,
                    )
                }
            }
            return StoreLayout(
                log,
                snapshot,
                logs,
                placements = snapshotPlacements,
                snapshotDefault = fallback
            ).also { layout ->
                layout.logStores.map(stores::getValue).groupBy { it.location }.values.filter { it.size > 1 }.forEach {
                    throw IllegalStateException(
                        "The log stores ${it.map(Store::name)} are on one database: each log group's partition " +
                            "sequences need a database of their own.",
                    )
                }
            }
        }

        /** [name], once [key] is found to name one of these stores, which [holds] [what]. */
        private fun Map<String, Store>.checked(
            key: String,
            name: String,
            holds: (Store) -> Boolean,
            what: String
        ): String {
            val store = checkNotNull(this[name]) { "$key names the store $name, which wow.stores does not: $keys." }
            check(holds(store)) { "$key names the store $name, of type ${store.type}, which holds no $what." }
            return name
        }

        /**
         * The snapshot placement `<key>.<list>` and `<key>.primary` configure (decision D172): the stores [targets]
         * lists, [fallback] when none; [primary], the first of them when not set, must be one of them.
         */
        private fun Map<String, Store>.placement(
            key: String,
            list: String,
            targets: List<String>,
            primary: String?,
            fallback: List<String>,
        ): SnapshotPlacement {
            val listed = targets.filter { it.isNotBlank() }.distinct()
            listed.forEach { checked("$key.$list", it, Store::holdsSnapshots, "snapshots") }
            val chosen = listed.ifEmpty { fallback }
            val first = primary?.takeIf { it.isNotBlank() } ?: chosen.first()
            check(first in chosen) { "$key.primary names $first, which is not one of its targets $chosen." }
            return SnapshotPlacement(chosen, first)
        }
    }
}

/**
 * Where a host's configuration places the logs and snapshots (decisions D172, D177), as the `wow.*` keys say: the
 * default [log] store (`wow.log.store`), the default [snapshot] store (`wow.snapshot.store`), the snapshot targets of
 * every aggregate ([snapshotTargets], `wow.snapshot.targets`, and [snapshotPrimary], `wow.snapshot.primary`), and each
 * aggregate's own, by `<context>.<aggregate>` ([aggregates], `wow.aggregates.[<context>.<aggregate>]`).
 */
@InternalWowApi
class StorePlacements(
    val log: String? = null,
    val snapshot: String? = null,
    val snapshotTargets: List<String> = emptyList(),
    val snapshotPrimary: String? = null,
    val aggregates: Map<String, AggregatePlacement> = emptyMap(),
)

/**
 * One aggregate type's stores (decision D177): its [log] store (`.log.store`), its [snapshots] targets (`.snapshot.store`)
 * and their [snapshotPrimary] (`.snapshot.primary`); none set, the defaults.
 */
@InternalWowApi
class AggregatePlacement(
    val log: String? = null,
    val snapshots: List<String> = emptyList(),
    val snapshotPrimary: String? = null,
)
