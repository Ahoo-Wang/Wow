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

import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.spi.snapshot.SnapshotStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The backends of the node (decision D110): the [BackendFactory] the [me.ahoo.wow.runtime.node.ServiceNode] opens
 * each context's stores from, remembering what it opened, so that the state reads find a context's stores and the
 * backend lifecycle ([WowLifecycles.BACKENDS_PHASE]) creates their schemas before the node starts.
 *
 * [kind] is the backend the host selected, or null for the application's own [BackendFactory] bean, whose schemas
 * are the application's; [appendQueue] is the backend's preset of the partition append queue. [shared] creates the
 * schemas of the stores the contexts share; [open] opens a context's stores and adds what creates their schemas.
 */
class HostBackends(
    val kind: BackendKind?,
    val appendQueue: AppendQueueOptions,
    shared: List<() -> Unit> = emptyList(),
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
            val checkpoints = InMemoryCheckpointStore()
            val leases = InMemoryLeaseStore()
            val ledger = InMemoryFailureLedger()
            val reservations = InMemoryReservationStore()
            return HostBackends(BackendKind.MEMORY, AppendQueueOptions()) { context, _ ->
                ContextBackends(
                    log = InMemoryEventLog(partitions),
                    checkpoints = checkpoints,
                    leases = leases,
                    ledger = ledger,
                    snapshots = snapshots(context),
                    reservations = reservations,
                    timers = InMemoryTimerStore(),
                )
            }
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
     * module on the classpath and a `DataSource` bean), MongoDB when [mongo] (its module and a `MongoClient` bean),
     * memory when neither. Both available is ambiguous, and a backend configured but unavailable fails.
     */
    fun select(configured: BackendKind, postgres: Boolean, mongo: Boolean): BackendKind = when (configured) {
        BackendKind.AUTO -> {
            check(!(postgres && mongo)) {
                "Both PostgreSQL (a DataSource) and MongoDB (a MongoClient) can back Wow: set wow.backend to one of them."
            }
            when {
                postgres -> BackendKind.POSTGRES
                mongo -> BackendKind.MONGO
                else -> BackendKind.MEMORY
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
}
