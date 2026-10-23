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
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The backends of the node (decision D110): the [BackendFactory] the [me.ahoo.wow.runtime.node.ServiceNode] opens
 * each context's stores from, remembering what it opened, so that the state reads find a context's stores and the
 * backend lifecycle ([WowLifecycles.BACKENDS_PHASE]) creates their schemas before the node starts.
 *
 * [type] is the store type the host selected (`memory` when any log is in memory, else the default log store's), or
 * null for the application's own [BackendFactory] bean, whose schemas are the application's; [local] whether a log is
 * on a store no other node shares ([Store.durable] false), so the node serves alone. [shared] creates the schemas of the stores the contexts share; [open] opens a
 * context's stores and adds what creates their schemas. [stores] are the node's store instances by name, and [layout]
 * where each aggregate's log and snapshots are (decision D177), when the host opened them.
 */
class HostBackends @InternalWowApi constructor(
    val type: String?,
    shared: List<() -> Unit> = emptyList(),
    @InternalWowApi
    val stores: Map<String, Store> = emptyMap(),
    @InternalWowApi
    val layout: StoreLayout? = null,
    /** Whether a log is on a store no other node shares, so that the node serves alone. */
    val local: Boolean = type == StoreTypes.MEMORY,
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
    fun snapshotStoreOf(type: AggregateType): Store? = layout?.let { stores[it.snapshotStoreOf(type)] }

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
         * [partitions] partitions.
         */
        @InternalWowApi
        fun of(stores: Map<String, Store>, layout: StoreLayout, partitions: Int): HostBackends {
            val logs = layout.logStores.map(stores::getValue)
            val local = logs.firstOrNull { !it.durable }
            return HostBackends(
                local?.type ?: stores.getValue(layout.log).type,
                stores.values.map { store -> { store.createSchemas() } },
                stores,
                layout,
                local = local != null,
            ) { context, _ -> layout.open(context, stores, partitions) }
        }

        /** The application's own [factory]: the host neither selects nor prepares its stores. */
        fun of(factory: BackendFactory): HostBackends =
            HostBackends(null, local = false) { context, _ -> factory.backends(context) }
    }
}
