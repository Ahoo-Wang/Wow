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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.springframework.beans.factory.ListableBeanFactory

/**
 * A store in memory (`memory`): for one node, whose data does not outlive it. Each context's snapshots come from
 * [snapshotsOf], an [InMemorySnapshotStore] unless a test wraps it. With [queries] (`wow.query.memory.enabled`, decision
 * D209), every snapshot written is also written there, the in-memory reference query backend the snapshot queries read.
 */
internal class MemoryStore(
    config: StoreConfig,
    val queries: InMemoryQueryStorage? = null,
    private val snapshotsOf: (context: String) -> SnapshotStore = { InMemorySnapshotStore() },
) : Store(config) {
    constructor(
        name: String,
        queries: InMemoryQueryStorage? = null,
        snapshotsOf: (context: String) -> SnapshotStore = { InMemorySnapshotStore() },
    ) : this(StoreConfig(name, StoreTypes.MEMORY), queries, snapshotsOf)

    override val location: Any get() = this

    override val durable: Boolean get() = false

    override fun openCheckpoints(): CheckpointStore = InMemoryCheckpointStore()

    override fun openLeases(): LeaseStore = InMemoryLeaseStore()

    override fun openLedger(): FailureLedger = InMemoryFailureLedger()

    override fun openReservations(): ReservationStore = InMemoryReservationStore()

    override fun openLog(context: String, partitions: Int): EventLog = InMemoryEventLog(partitions)

    override fun openSnapshots(context: String): SnapshotStore =
        snapshotsOf(context).let { store -> queries?.let { QueriedSnapshotStore(store, it) } ?: store }

    override fun openTimers(context: String): TimerStore = InMemoryTimerStore()
}

/**
 * The memory stores' provider, built into the host: their snapshots queryable when [memoryQueries]
 * (`wow.query.memory.enabled`, decision D209).
 */
internal class MemoryStoreProvider(private val memoryQueries: Boolean = false) : StoreProvider {
    override val type: String = StoreTypes.MEMORY

    override val requirement: String = "nothing"

    override fun open(config: StoreConfig): Store =
        MemoryStore(config, queries = InMemoryQueryStorage().takeIf { memoryQueries })
}

/** A snapshot store that also writes each snapshot it writes into [queries], the snapshot queries' storage. */
internal class QueriedSnapshotStore(private val store: SnapshotStore, private val queries: InMemoryQueryStorage) :
    SnapshotStore by store {
    override suspend fun save(snapshot: SnapshotRecord): Boolean =
        store.save(snapshot).also { written -> if (written) queries.save(snapshot) }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean =
        store.rewrite(snapshot).also { written -> if (written) queries.rewrite(snapshot) }
}

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
     * aggregates' logs are on; its snapshot targets, each type's as its placement says.
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
         * The layout [properties] configure over [stores], the node's store instances by name, for the aggregate
         * types of [models]. Refused: a default or per-type store that names no store, or one that holds no log or no
         * snapshots ([Store.holdsLogs], [Store.holdsSnapshots]); a `wow.aggregates` key of a type the node does not serve; more than one store and
         * no `wow.log.store`; two log stores on one database.
         */
        fun of(
            properties: WowProperties,
            stores: Map<String, Store>,
            models: List<AggregateModel<*>>,
        ): StoreLayout {
            require(stores.isNotEmpty()) { "The node has no store." }
            val log = properties.log.store?.takeIf { it.isNotBlank() } ?: stores.keys.singleOrNull()
                ?: throw IllegalStateException(
                    "wow.stores names ${stores.keys}: set wow.log.store to the one the logs are on by default.",
                )
            val snapshot = properties.snapshot.store?.takeIf { it.isNotBlank() } ?: log
            stores.checked("wow.log.store", log, Store::holdsLogs, "log")
            stores.checked("wow.snapshot.store", snapshot, Store::holdsSnapshots, "snapshots")
            val fallback = stores.placement(
                "wow.snapshot",
                "targets",
                properties.snapshot.targets,
                properties.snapshot.primary,
                listOf(snapshot),
            )
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val logs = LinkedHashMap<AggregateType, String>()
            val placements = LinkedHashMap<AggregateType, SnapshotPlacement>()
            properties.aggregates.forEach { (key, aggregate) ->
                val logStore = aggregate.log.store?.takeIf { it.isNotBlank() }
                val snapshots = aggregate.snapshot
                val placed = snapshots.store.any { it.isNotBlank() } || !snapshots.primary.isNullOrBlank()
                if (logStore == null && !placed) {
                    return@forEach
                }
                val type = checkNotNull(served[key]) {
                    "wow.aggregates.[$key] names the stores of no aggregate type this node serves; it serves ${served.keys}."
                }.type
                logStore?.let { logs[type] = stores.checked("wow.aggregates.[$key].log.store", it, Store::holdsLogs, "log") }
                if (placed) {
                    placements[type] = stores.placement(
                        "wow.aggregates.[$key].snapshot",
                        "store",
                        snapshots.store,
                        snapshots.primary,
                        fallback.targets,
                    )
                }
            }
            return StoreLayout(
                log,
                snapshot,
                logs,
                placements = placements,
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

/** Opens the node's store instances (decision D177) through the store providers ([StoreProviders]). */
internal object StoreInstances {
    /**
     * The stores of `wow.stores`, by name, each opened by the provider of its `type`; when it names none, the one store
     * `wow.backend` selects ([BackendSelection]), named [ContextBackends.DEFAULT_STORE]. Each store is given its
     * settings as options: `data-source`, `client`, `database` (`wow.mongo.database` when not set) and its `options.*`,
     * the application's beans as [resources], `wow.ddl` unless it names its own, the tails' polling and the [service].
     * `wow.backend=auto` without a durable backend opens a store in memory only with a [memoryOptIn]
     * ([BackendSelection.memoryOptIn]), and fails otherwise (review R3.5-1).
     */
    @Suppress("LongParameterList") // One parameter per input of the stores, each the host's own.
    fun open(
        properties: WowProperties,
        providers: StoreProviders,
        resources: StoreResources,
        service: String = "",
        memoryOptIn: String? = null,
    ): Map<String, Store> {
        val polling = properties.log.tailPolling()
        fun config(name: String, type: String, store: WowProperties.Store?) = StoreConfig(
            name = name,
            type = type,
            ddl = store?.ddl ?: properties.ddl,
            options = store?.options().orEmpty() + mapOf(DATABASE to properties.mongo.database).filterKeys {
                store?.database.isNullOrBlank()
            },
            polling = polling,
            service = service,
            resources = resources,
        )
        if (properties.stores.isEmpty()) {
            val name = ContextBackends.DEFAULT_STORE
            val type = BackendSelection.select(properties.backend, providers, resources, memoryOptIn)
            return mapOf(name to providers.of(type, "wow.backend").open(config(name, type, null)))
        }
        check(StoreTypes.of(properties.backend) == StoreTypes.AUTO) {
            "wow.backend is ${properties.backend}, but wow.stores names the stores: give each its type there instead."
        }
        return properties.stores.mapValues { (name, store) ->
            val key = "wow.stores.$name"
            val type = StoreTypes.of(
                checkNotNull(store.type?.takeIf { it.isNotBlank() }) {
                    "$key.type is not set: ${providers.types.joinToString()}."
                },
            )
            check(type != StoreTypes.AUTO) { "$key.type is auto: name it, ${providers.types.joinToString()}." }
            providers.of(type, "$key.type").open(config(name, type, store))
        }
    }

    /** The option of the MongoDB database, which `wow.mongo.database` gives a store that names none. */
    private const val DATABASE = "database"
}

/** The application's beans as the resources its stores are opened over (a `DataSource`, a `MongoClient`). */
internal class BeanStoreResources(private val beanFactory: ListableBeanFactory) : StoreResources {
    override fun has(type: Class<*>): Boolean = beanFactory.getBeanNamesForType(type).isNotEmpty()

    override fun <T : Any> resource(type: Class<T>, name: String?, key: String): T {
        if (name.isNullOrBlank()) {
            check(has(type)) { "$key is not set, and there is no ${type.simpleName} bean." }
            return beanFactory.getBean(type)
        }
        check(beanFactory.containsBean(name) && beanFactory.isTypeMatch(name, type)) {
            "$key names $name, which is no ${type.simpleName} bean."
        }
        return beanFactory.getBean(name, type)
    }
}
