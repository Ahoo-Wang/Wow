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

import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.store.BackendSelection
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.springframework.beans.factory.ListableBeanFactory
import kotlin.time.toKotlinDuration

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

    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
        store.saveAll(snapshots).also { outcomes ->
            outcomes.forEachIndexed { index, outcome -> if (outcome.written) queries.save(snapshots[index]) }
        }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean =
        store.rewrite(snapshot).also { written -> if (written) queries.rewrite(snapshot) }
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
            options = mapOf(
                APPEND_TIMEOUT to properties.mongo.appendTimeout.toKotlinDuration().toString(),
                SHARDING to properties.mongo.sharding,
                OPERATION_TIMEOUT to properties.mongo.operationTimeout.toKotlinDuration().toString(),
            ) +
                store?.options().orEmpty() + mapOf(DATABASE to properties.mongo.database).filterKeys {
                    store?.database.isNullOrBlank()
                },
            polling = polling,
            service = service,
            resources = resources,
        )
        if (properties.stores.isEmpty()) {
            val name = ContextBackends.DEFAULT_STORE
            val type = BackendSelection.select(properties.backend, providers.stores, resources, memoryOptIn)
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

    /** The option bounding a MongoDB append's transaction, which `wow.mongo.transaction-timeout` gives by default. */
    private const val APPEND_TIMEOUT = "append-timeout"
    private const val SHARDING = "sharding"
    private const val OPERATION_TIMEOUT = "operation-timeout"
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
