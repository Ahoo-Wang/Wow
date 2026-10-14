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
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.snapshot.SnapshotStoreRouter
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.util.ClassUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * One store instance of the node, `wow.stores.<name>` (decision D177), of a [kind]: it opens each context's log,
 * snapshots and timers on their first use, and the checkpoints, leases, failure ledger and reservations the contexts
 * whose stores it holds share, and remembers what creates their schemas ([createSchemas]).
 *
 * Its location is the database it writes in: two stores whose logs share one would share their partition sequences.
 */
abstract class HostStore internal constructor(val name: String, val kind: BackendKind) {
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
 * [InMemorySnapshotStore] unless a test wraps it.
 */
class MemoryStore(
    name: String,
    private val snapshotsOf: (context: String) -> SnapshotStore = { InMemorySnapshotStore() },
) : HostStore(name, BackendKind.MEMORY) {
    override val location: Any get() = this

    override fun openCheckpoints(): CheckpointStore = InMemoryCheckpointStore()

    override fun openLeases(): LeaseStore = InMemoryLeaseStore()

    override fun openLedger(): FailureLedger = InMemoryFailureLedger()

    override fun openReservations(): ReservationStore = InMemoryReservationStore()

    override fun openLog(context: String, partitions: Int): EventLog = InMemoryEventLog(partitions)

    override fun openSnapshots(context: String): SnapshotStore = snapshotsOf(context)

    override fun openTimers(context: String): TimerStore = InMemoryTimerStore()
}

/**
 * Where each aggregate's log and snapshots are (decision D177): on the store [log] names (`wow.log.store`) and the one
 * [snapshot] names (`wow.snapshot.store`), unless the logs or snapshots maps name another for its type
 * (`wow.aggregates.[<context>.<aggregate>].log.store`, `.snapshot.store`).
 */
class StoreLayout(
    val log: String,
    val snapshot: String = log,
    private val logs: Map<AggregateType, String> = emptyMap(),
    private val snapshots: Map<AggregateType, String> = emptyMap(),
) {
    /** The store of [type]'s log: its log group. */
    fun logStoreOf(type: AggregateType): String = logs[type] ?: log

    /** The store of [type]'s snapshots, which its snapshot queries read. */
    fun snapshotStoreOf(type: AggregateType): String = snapshots[type] ?: snapshot

    /** The stores some log is on. */
    val logStores: Set<String> get() = setOf(log) + logs.values

    /** The stores some snapshots are on. */
    val snapshotStores: Set<String> get() = setOf(snapshot) + snapshots.values

    /**
     * The stores of [context] among [stores], each opened as this layout says: its default log group on [log], with
     * the checkpoints, leases, failure ledger, reservations and timers of that store; a log group per other store its
     * aggregates' logs are on; its snapshots on [snapshot], or the store its type names.
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
        val snapshotsByType = snapshots.filter { (type, store) -> type.context == context && store != snapshot }
            .mapValues { stores.getValue(it.value).snapshots(context) }
        return ContextBackends(
            log = default.log(context, partitions),
            checkpoints = default.checkpoints,
            leases = default.leases,
            ledger = default.ledger,
            snapshots = SnapshotStoreRouter.of(stores.getValue(snapshot).snapshots(context), snapshotsByType),
            reservations = default.reservations,
            timers = default.timers(context),
            store = log,
            groups = groups,
        )
    }

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
            fun checked(key: String, name: String, kinds: Set<BackendKind>, what: String): String {
                val store = checkNotNull(
                    stores[name]
                ) { "$key names the store $name, which wow.stores does not: ${stores.keys}." }
                check(
                    store.kind in kinds
                ) { "$key names the store $name, of type ${store.kind}, which holds no $what." }
                return name
            }
            checked("wow.log.store", log, LOG_KINDS, "log")
            checked("wow.snapshot.store", snapshot, SNAPSHOT_KINDS, "snapshots")
            val served = models.associateBy { "${it.type.context}.${it.type.name}" }
            val logs = LinkedHashMap<AggregateType, String>()
            val snapshots = LinkedHashMap<AggregateType, String>()
            properties.aggregates.forEach { (key, aggregate) ->
                val logStore = aggregate.log.store?.takeIf { it.isNotBlank() }
                val snapshotStore = aggregate.snapshot.store?.takeIf { it.isNotBlank() }
                if (logStore == null && snapshotStore == null) {
                    return@forEach
                }
                val type = checkNotNull(served[key]) {
                    "wow.aggregates.[$key] names the stores of no aggregate type this node serves; it serves ${served.keys}."
                }.type
                logStore?.let { logs[type] = checked("wow.aggregates.[$key].log.store", it, LOG_KINDS, "log") }
                snapshotStore?.let {
                    snapshots[type] = checked("wow.aggregates.[$key].snapshot.store", it, SNAPSHOT_KINDS, "snapshots")
                }
            }
            return StoreLayout(log, snapshot, logs, snapshots).also { layout ->
                layout.logStores.map(stores::getValue).groupBy { it.location }.values.filter { it.size > 1 }.forEach {
                    throw IllegalStateException(
                        "The log stores ${it.map(HostStore::name)} are on one database: each log group's partition " +
                            "sequences need a database of their own.",
                    )
                }
            }
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
     * MongoDB wow-mongo and a `MongoClient` bean: the one its `data-source` or `client` names, else the only one.
     */
    fun open(
        properties: WowProperties,
        beanFactory: ListableBeanFactory,
        classLoader: ClassLoader?
    ): Map<String, HostStore> {
        val postgresModule = ClassUtils.isPresent(POSTGRES_LOG, classLoader)
        val mongoClient = MONGO_CLIENT.takeIf { ClassUtils.isPresent(MONGO_LOG, classLoader) }
            ?.takeIf { ClassUtils.isPresent(it, classLoader) }
            ?.let { ClassUtils.forName(it, classLoader) }
        if (properties.stores.isEmpty()) {
            val postgres = postgresModule && beanFactory.getBeanNamesForType(DataSource::class.java).isNotEmpty()
            val mongo = mongoClient != null && beanFactory.getBeanNamesForType(mongoClient).isNotEmpty()
            val name = ContextBackends.DEFAULT_STORE
            val store = when (BackendSelection.select(properties.backend, postgres, mongo)) {
                BackendKind.POSTGRES -> PostgresStore.open(name, beanFactory.getBean(DataSource::class.java))
                BackendKind.MONGO ->
                    MongoStore.open(name, beanFactory.getBean(mongoClient!!), properties.mongo.database)

                else -> MemoryStore(name)
            }
            return mapOf(name to store)
        }
        check(properties.backend == BackendKind.AUTO) {
            "wow.backend is ${properties.backend}, but wow.stores names the stores: give each its type there instead."
        }
        return properties.stores.mapValues { (name, store) ->
            val key = "wow.stores.$name"
            when (checkNotNull(store.type) { "$key.type is not set: memory, postgres or mongo." }) {
                BackendKind.MEMORY -> MemoryStore(name)
                BackendKind.POSTGRES -> {
                    check(postgresModule) { "$key is on PostgreSQL, which needs wow-postgres on the classpath." }
                    PostgresStore.open(
                        name,
                        bean(beanFactory, DataSource::class.java, store.dataSource, "$key.data-source")
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
