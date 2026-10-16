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
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.util.ClassUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One store instance of the node, `wow.stores.<name>` (decision D177), of a [kind]: it opens each context's log,
 * snapshots and timers on their first use, and the checkpoints, leases, failure ledger and reservations the contexts
 * whose stores it holds share, and remembers what creates their schemas ([createSchemas]).
 *
 * Its location is the database it writes in: two stores whose logs share one would share their partition sequences.
 */
@InternalWowApi
abstract class HostStore internal constructor(val name: String, val kind: BackendKind) {
    /** How the tails of the logs this store opens poll (`wow.log.poll-interval`, finding A2-14). */
    internal var tailPolling: TailPolling = TailPolling.DEFAULT

    /** The service whose failure ledger this store keeps: the key of its entries (finding A2-13). */
    internal var service: String = ""

    private val schemas = CopyOnWriteArrayList<() -> Unit>()
    private val logs = ConcurrentHashMap<String, EventLog>()
    private val snapshotStores = ConcurrentHashMap<String, SnapshotStore>()
    private val timerStores = ConcurrentHashMap<String, TimerStore>()

    /** The database the store writes in, as two stores would share it. */
    internal abstract val location: Any

    val checkpoints: CheckpointStore by lazy { openCheckpoints() }
    val leases: LeaseStore by lazy { openLeases() }
    val ledger: FailureLedger by lazy { openLedger() }
    val reservations: ReservationStore by lazy { openReservations() }

    /** [context]'s log on this store, of [partitions] partitions. */
    fun log(context: String, partitions: Int): EventLog = logs.computeIfAbsent(context) { openLog(it, partitions) }

    /** [context]'s snapshots on this store. */
    fun snapshots(context: String): SnapshotStore = snapshotStores.computeIfAbsent(context, ::openSnapshots)

    /** [context]'s timers on this store. */
    fun timers(context: String): TimerStore = timerStores.computeIfAbsent(context, ::openTimers)

    /** Creates the schemas of what the store opened so far, where they are missing. */
    fun createSchemas() {
        schemas.forEach { it() }
    }

    /** Remembers that [create] creates the schema of what was just opened. */
    protected fun schema(create: () -> Unit) {
        schemas += create
    }

    protected abstract fun openCheckpoints(): CheckpointStore

    protected abstract fun openLeases(): LeaseStore

    protected abstract fun openLedger(): FailureLedger

    protected abstract fun openReservations(): ReservationStore

    protected abstract fun openLog(context: String, partitions: Int): EventLog

    protected abstract fun openSnapshots(context: String): SnapshotStore

    protected abstract fun openTimers(context: String): TimerStore

    override fun toString(): String = "$name ($kind)"
}

/**
 * A store in memory: for one node, whose data does not outlive it. Each context's snapshots come from [snapshotsOf], an
 * [InMemorySnapshotStore] unless a test wraps it. With [queries] (`wow.query.memory.enabled`, decision D209), every
 * snapshot written is also written there, the in-memory reference query backend the snapshot queries read.
 */
internal class MemoryStore(
    name: String,
    val queries: InMemoryQueryStorage? = null,
    private val snapshotsOf: (context: String) -> SnapshotStore = { InMemorySnapshotStore() },
) : HostStore(name, BackendKind.MEMORY) {
    override val location: Any get() = this

    override fun openCheckpoints(): CheckpointStore = InMemoryCheckpointStore()

    override fun openLeases(): LeaseStore = InMemoryLeaseStore()

    override fun openLedger(): FailureLedger = InMemoryFailureLedger()

    override fun openReservations(): ReservationStore = InMemoryReservationStore()

    override fun openLog(context: String, partitions: Int): EventLog = InMemoryEventLog(partitions)

    override fun openSnapshots(context: String): SnapshotStore =
        snapshotsOf(context).let { store -> queries?.let { QueriedSnapshotStore(store, it) } ?: store }

    override fun openTimers(context: String): TimerStore = InMemoryTimerStore()
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
    fun open(context: String, stores: Map<String, HostStore>, partitions: Int): ContextBackends {
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
        /** The store types a log can be on, and those snapshots can. */
        private val LOG_KINDS = setOf(BackendKind.MEMORY, BackendKind.POSTGRES, BackendKind.MONGO)
        private val SNAPSHOT_KINDS = LOG_KINDS

        /**
         * The layout [properties] configure over [stores], the node's store instances by name, for the aggregate
         * types of [models]. Refused: a default or per-type store that names no store, or one of a type that cannot
         * hold a log or snapshots; a `wow.aggregates` key of a type the node does not serve; more than one store and
         * no `wow.log.store`; two log stores on one database.
         */
        fun of(
            properties: WowProperties,
            stores: Map<String, HostStore>,
            models: List<AggregateModel<*>>,
        ): StoreLayout {
            require(stores.isNotEmpty()) { "The node has no store." }
            val log = properties.log.store?.takeIf { it.isNotBlank() } ?: stores.keys.singleOrNull()
                ?: throw IllegalStateException(
                    "wow.stores names ${stores.keys}: set wow.log.store to the one the logs are on by default.",
                )
            val snapshot = properties.snapshot.store?.takeIf { it.isNotBlank() } ?: log
            stores.checked("wow.log.store", log, LOG_KINDS, "log")
            stores.checked("wow.snapshot.store", snapshot, SNAPSHOT_KINDS, "snapshots")
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
                logStore?.let { logs[type] = stores.checked("wow.aggregates.[$key].log.store", it, LOG_KINDS, "log") }
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
                        "The log stores ${it.map(HostStore::name)} are on one database: each log group's partition " +
                            "sequences need a database of their own.",
                    )
                }
            }
        }

        /** [name], once [key] is found to name one of these stores, of one of [kinds], which holds [what]. */
        private fun Map<String, HostStore>.checked(
            key: String,
            name: String,
            kinds: Set<BackendKind>,
            what: String
        ): String {
            val store = checkNotNull(this[name]) { "$key names the store $name, which wow.stores does not: $keys." }
            check(store.kind in kinds) { "$key names the store $name, of type ${store.kind}, which holds no $what." }
            return name
        }

        /**
         * The snapshot placement `<key>.<list>` and `<key>.primary` configure (decision D172): the stores [targets]
         * lists, [fallback] when none; [primary], the first of them when not set, must be one of them.
         */
        private fun Map<String, HostStore>.placement(
            key: String,
            list: String,
            targets: List<String>,
            primary: String?,
            fallback: List<String>,
        ): SnapshotPlacement {
            val listed = targets.filter { it.isNotBlank() }.distinct()
            listed.forEach { checked("$key.$list", it, SNAPSHOT_KINDS, "snapshots") }
            val chosen = listed.ifEmpty { fallback }
            val first = primary?.takeIf { it.isNotBlank() } ?: chosen.first()
            check(first in chosen) { "$key.primary names $first, which is not one of its targets $chosen." }
            return SnapshotPlacement(chosen, first)
        }
    }
}

/** Opens the node's store instances (decision D177) from what the classpath and the application's beans offer. */
internal object StoreInstances {
    private const val POSTGRES_LOG = "me.ahoo.wow.postgres.PostgresEventLog"
    private const val MONGO_LOG = "me.ahoo.wow.mongo.log.MongoEventLog"
    private const val MONGO_CLIENT = "com.mongodb.client.MongoClient"

    /**
     * The stores of `wow.stores`, by name; when it names none, the one store `wow.backend` selects ([BackendSelection]),
     * named [ContextBackends.DEFAULT_STORE]. A store on PostgreSQL needs wow-postgres and a `DataSource` bean, one on
     * MongoDB wow-mongo and a `MongoClient` bean: the one its `data-source` or `client` names, else the only one. A
     * store in memory keeps its snapshots queryable when [memoryQueries] (`wow.query.memory.enabled`, decision D209).
     */
    fun open(
        properties: WowProperties,
        beanFactory: ListableBeanFactory,
        classLoader: ClassLoader?,
        memoryQueries: Boolean = false,
        service: String = "",
    ): Map<String, HostStore> {
        val polling = properties.log.tailPolling()
        return stores(properties, beanFactory, classLoader, memoryQueries).onEach { (_, store) ->
            store.tailPolling = polling
            store.service = service
        }
    }

    private fun stores(
        properties: WowProperties,
        beanFactory: ListableBeanFactory,
        classLoader: ClassLoader?,
        memoryQueries: Boolean = false,
    ): Map<String, HostStore> {
        fun memory(name: String) = MemoryStore(name, queries = InMemoryQueryStorage().takeIf { memoryQueries })
        val postgresModule = ClassUtils.isPresent(POSTGRES_LOG, classLoader)
        val mongoClient = MONGO_CLIENT.takeIf { ClassUtils.isPresent(MONGO_LOG, classLoader) }
            ?.takeIf { ClassUtils.isPresent(it, classLoader) }
            ?.let { ClassUtils.forName(it, classLoader) }
        if (properties.stores.isEmpty()) {
            val postgres = postgresModule && beanFactory.getBeanNamesForType(DataSource::class.java).isNotEmpty()
            val mongo = mongoClient != null && beanFactory.getBeanNamesForType(mongoClient).isNotEmpty()
            val name = ContextBackends.DEFAULT_STORE
            val store = when (BackendSelection.select(properties.backend, postgres, mongo)) {
                BackendKind.POSTGRES -> PostgresStore.open(
                    name,
                    beanFactory.getBean(DataSource::class.java),
                    properties.ddl
                )
                BackendKind.MONGO ->
                    MongoStore.open(name, beanFactory.getBean(mongoClient!!), properties.mongo.database, properties.ddl)

                else -> memory(name)
            }
            return mapOf(name to store)
        }
        check(properties.backend == BackendKind.AUTO) {
            "wow.backend is ${properties.backend}, but wow.stores names the stores: give each its type there instead."
        }
        return properties.stores.mapValues { (name, store) ->
            val key = "wow.stores.$name"
            val ddl = store.ddlOr(properties.ddl)
            when (checkNotNull(store.type) { "$key.type is not set: memory, postgres or mongo." }) {
                BackendKind.MEMORY -> memory(name)
                BackendKind.POSTGRES -> {
                    check(postgresModule) { "$key is on PostgreSQL, which needs wow-postgres on the classpath." }
                    PostgresStore.open(
                        name,
                        bean(beanFactory, DataSource::class.java, store.dataSource, "$key.data-source"),
                        ddl,
                    )
                }

                BackendKind.MONGO -> {
                    val client = checkNotNull(
                        mongoClient
                    ) { "$key is on MongoDB, which needs wow-mongo on the classpath." }
                    MongoStore.open(
                        name,
                        bean(beanFactory, client, store.client, "$key.client"),
                        store.database?.takeIf { it.isNotBlank() } ?: properties.mongo.database,
                        ddl,
                    )
                }

                BackendKind.AUTO -> throw IllegalStateException(
                    "$key.type is auto: name it, memory, postgres or mongo."
                )
            }
        }
    }

    /** The bean of [type] [name] names, else the only one (or the primary one). */
    private fun bean(beanFactory: ListableBeanFactory, type: Class<*>, name: String?, key: String): Any {
        if (name.isNullOrBlank()) {
            check(
                beanFactory.getBeanNamesForType(type).isNotEmpty()
            ) { "$key is not set, and there is no ${type.simpleName} bean." }
            return beanFactory.getBean(type)
        }
        check(beanFactory.containsBean(name) && beanFactory.isTypeMatch(name, type)) {
            "$key names $name, which is no ${type.simpleName} bean."
        }
        return beanFactory.getBean(name)
    }
}

/**
 * How often the tails of a log on PostgreSQL or MongoDB poll (finding A2-14): every [interval] while they find
 * entries, backing off up to [maxInterval] while they find none.
 */
internal data class TailPolling(val interval: Duration, val maxInterval: Duration) {
    init {
        require(interval.isPositive()) { "wow.log.poll-interval is positive." }
        require(maxInterval >= interval) { "wow.log.max-poll-interval is at least wow.log.poll-interval." }
    }

    companion object {
        val DEFAULT = TailPolling(50.milliseconds, 1.seconds)
    }
}
