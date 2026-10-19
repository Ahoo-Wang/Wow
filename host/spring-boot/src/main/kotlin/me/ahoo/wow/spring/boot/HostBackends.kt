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
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.spi.snapshot.SnapshotStore
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The backends of the node (decision D110): the [BackendFactory] the [me.ahoo.wow.runtime.node.ServiceNode] opens
 * each context's stores from, remembering what it opened, so that the state reads find a context's stores and the
 * backend lifecycle ([WowLifecycles.BACKENDS_PHASE]) creates their schemas before the node starts.
 *
 * [kind] is the backend the host selected (memory when any log is in memory, else the default log store's), or null
 * for the application's own [BackendFactory] bean, whose schemas are the application's; [appendQueue] is the backends'
 * preset of the partition append queue. [shared] creates the schemas of the stores the contexts share; [open] opens a
 * context's stores and adds what creates their schemas. [stores] are the node's store instances by name, and [layout]
 * where each aggregate's log and snapshots are (decision D177), when the host opened them.
 */
class HostBackends @InternalWowApi constructor(
    val kind: BackendKind?,
    val appendQueue: AppendQueueOptions,
    shared: List<() -> Unit> = emptyList(),
    val stores: Map<String, HostStore> = emptyMap(),
    @InternalWowApi
    val layout: StoreLayout? = null,
    private val open: (context: String, schemas: MutableList<() -> Unit>) -> ContextBackends,
) {
    private val opened = ConcurrentHashMap<String, ContextBackends>()
    private val schemas = CopyOnWriteArrayList(shared)

    /** Opens each context's stores once. */
    val factory: BackendFactory = BackendFactory { context ->
        opened.computeIfAbsent(context) { open(it, schemas) }
    }

    /** The stores of each context opened so far. */
    val contexts: Map<String, ContextBackends> get() = opened.toMap()

    /** Creates the schemas of the stores opened so far, where they are missing. */
    fun createSchemas() {
        schemas.forEach { it() }
    }

    /** The store instance [type]'s snapshots are on, when the host opened its stores. */
    @InternalWowApi
    fun snapshotStoreOf(type: AggregateType): HostStore? = layout?.let { stores[it.snapshotStoreOf(type)] }

    companion object {
        /**
         * In memory, each context its own log, snapshots and timers: for one node, whose data does not outlive it.
         * [snapshots] opens each context's snapshot store, an [InMemorySnapshotStore] unless a test wraps it (to
         * query the snapshots on the memory backend, say).
         */
        fun memory(
            partitions: Int,
            snapshots: (context: String) -> SnapshotStore = { InMemorySnapshotStore() },
        ): HostBackends {
            val store = MemoryStore(ContextBackends.DEFAULT_STORE, snapshotsOf = snapshots)
            return of(mapOf(store.name to store), StoreLayout(store.name), partitions)
        }

        /**
         * The node's [stores] by name, each context's stores opened as [layout] says (decision D177), its logs of
         * [partitions] partitions. The append queue is MongoDB's preset when a log is on MongoDB, else PostgreSQL's
         * when one is on PostgreSQL.
         */
        @InternalWowApi
        fun of(stores: Map<String, HostStore>, layout: StoreLayout, partitions: Int): HostBackends {
            val logs = layout.logStores.map(stores::getValue)
            val kind = if (logs.any { it.kind == BackendKind.MEMORY }) {
                BackendKind.MEMORY
            } else {
                stores.getValue(layout.log).kind
            }
            val appendQueue = when {
                logs.any { it.kind == BackendKind.MONGO } -> AppendQueueOptions.MONGO
                logs.any { it.kind == BackendKind.POSTGRES } -> AppendQueueOptions.POSTGRES
                else -> AppendQueueOptions()
            }
            return HostBackends(
                kind,
                appendQueue,
                stores.values.map { store -> { store.createSchemas() } },
                stores,
                layout,
            ) { context, _ -> layout.open(context, stores, partitions) }
        }

        /** The application's own [factory]: the host neither selects nor prepares its stores. */
        fun of(factory: BackendFactory): HostBackends =
            HostBackends(null, AppendQueueOptions()) { context, _ -> factory.backends(context) }
    }
}

/** Which backend a node runs on (`wow.backend`, decision D110). */
internal object BackendSelection {
    /**
     * The backend [configured], [BackendKind.AUTO] resolved by what is available: PostgreSQL when [postgres] (its
     * module on the classpath and a `DataSource` bean), MongoDB when [mongo] (its module and a `MongoClient` bean).
     * Both available is ambiguous, and a backend configured but unavailable fails.
     *
     * Neither available fails too (review R3.5-1): a node in memory loses every committed fact when it stops, and two
     * of them would each own every partition. AUTO falls back to memory only with a [memoryOptIn], the reason a
     * development node may keep its data there (see [memoryOptIn] below); a node meant to run in memory names it,
     * `wow.backend=memory`.
     */
    fun select(
        configured: BackendKind,
        postgres: Boolean,
        mongo: Boolean,
        memoryOptIn: String? = null,
    ): BackendKind = when (configured) {
        BackendKind.AUTO -> {
            check(!(postgres && mongo)) {
                "Both PostgreSQL (a DataSource) and MongoDB (a MongoClient) can back Wow: set wow.backend to one of them."
            }
            when {
                postgres -> BackendKind.POSTGRES
                mongo -> BackendKind.MONGO
                else -> BackendKind.MEMORY.also { checkNotNull(memoryOptIn) { NO_DURABLE_BACKEND } }
            }
        }

        BackendKind.POSTGRES -> configured.also {
            check(postgres) { "wow.backend=postgres needs wow-postgres on the classpath and a DataSource bean." }
        }

        BackendKind.MONGO -> configured.also {
            check(mongo) { "wow.backend=mongo needs wow-mongo on the classpath and a MongoClient bean." }
        }

        BackendKind.MEMORY -> configured
    }

    /**
     * Why a node may fall back to memory when `wow.backend` is auto and no durable backend is available, or null when
     * it may not: an active profile of [devProfiles] (`wow.internal.dev-profiles`), or an internal port bound to
     * [bindHost], a loopback address, alone, the rule a node without a cluster token follows (decision D226).
     */
    fun memoryOptIn(activeProfiles: Collection<String>, devProfiles: Set<String>, bindHost: String?): String? {
        val dev = activeProfiles.filter { it in devProfiles }
        return when {
            dev.isNotEmpty() -> "profile ${dev.joinToString()}"
            bindHost != null && isLoopback(bindHost) -> "internal port on loopback $bindHost"
            else -> null
        }
    }

    private fun isLoopback(host: String): Boolean = try {
        InetAddress.getByName(host).isLoopbackAddress
    } catch (_: UnknownHostException) {
        false
    }

    const val NO_DURABLE_BACKEND: String =
        "No durable backend for Wow: wow.backend is auto, but neither PostgreSQL (wow-postgres on the classpath and a " +
            "DataSource bean) nor MongoDB (wow-mongo and a MongoClient bean) is available, and a node in memory loses " +
            "every command it committed when it stops (review R3.5-1). Add the backend's module and its DataSource or " +
            "MongoClient, or set wow.backend=memory to keep the data in memory on purpose. A profile of " +
            "wow.internal.dev-profiles, or an internal port bound to the loopback interface alone, also runs in memory."
}
