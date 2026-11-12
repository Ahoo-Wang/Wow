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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A node of one context over a log and a lease store that fail on demand, and the health a host makes over its sampler:
 * what the node's health tests share, each test with its own.
 */
abstract class NodeHealthFixtures {
    protected val channel = LoopbackClusterChannel()
    protected val nodes = CopyOnWriteArrayList<ServiceNode>()
    protected val healths = CopyOnWriteArrayList<NodeHealth>()
    protected val samplers = HashMap<NodeHealth, NodeSampler>()
    protected val log = FlakyLog(InMemoryEventLog(PARTITIONS))
    protected val leases = FlakyLeases(InMemoryLeaseStore())

    /** What a slow subscription waits for. */
    protected val slow = CompletableDeferred<Unit>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        log.hang?.complete(Unit)
        log.hang = null
        // A gated acquisition left waiting would hold the reconcile, and so the node's stop.
        leases.acquiring?.complete(Unit)
        leases.acquiring = null
        slow.complete(Unit)
        healths.forEach { it.close() }
        samplers.values.forEach { it.close() }
        nodes.forEach { it.stop() }
    }

    protected val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = leases,
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
        )
    }

    /** A store instance whose probe fails while [down], counting how often it is closed. */
    protected class ProbedStore(name: String, type: String) : Store(StoreConfig.of(name, type)) {
        @Volatile
        var down = false
        var closes = 0
        override val location: Any = Any()

        override suspend fun probe() = check(!down) { "$name is down." }

        override fun closeStore() {
            closes++
        }

        override fun openCheckpoints(): Opened<CheckpointStore> = error("unused")

        override fun openLeases(): Opened<LeaseStore> = error("unused")

        override fun openLedger(): Opened<FailureLedger> = error("unused")

        override fun openReservations(): Opened<ReservationStore> = error("unused")

        override fun openLog(context: String, partitions: Int): Opened<EventLog> = error("unused")

        override fun openSnapshots(context: String): Opened<SnapshotStore> = error("unused")

        override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = error("unused")
    }

    protected fun node(
        model: AggregateModel<Account> = account(),
        options: ServiceNodeOptions = fastNodeOptions(),
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
        backends: BackendFactory = this.backends,
    ): ServiceNode = ServiceNode(
        NODE_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(model),
        accountsManifest(BANK),
        backends,
        channel,
        options = options,
        subscriptions = subscriptions,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    protected fun health(node: ServiceNode, options: HealthOptions = OPTIONS): NodeHealth =
        healthOf(node, options) { sampler -> NodeHealth(node, sampler, options) }

    /** The health [create]d over a sampler of [node] as a host makes one, its reads bounded as a probe is. */
    protected fun healthOf(node: ServiceNode, options: HealthOptions, create: (NodeSampler) -> NodeHealth): NodeHealth {
        val sampler = NodeSampler(
            node,
            options.sampleInterval,
            readTimeout = options.storeDownAfter.coerceAtLeast(1.seconds),
        )
        return create(sampler).also {
            healths += it
            samplers[it] = sampler
        }
    }

    /** How long [health]'s liveness says the node's commands in flight have made no progress, in milliseconds. */
    protected fun progressMillis(health: NodeHealth): Long = health.liveness().details["lastProgressMillis"] as Long

    /** Starts [health]'s tick and its sampler, as a host starts both. */
    protected fun start(health: NodeHealth) {
        health.start()
        samplers.getValue(health).start()
    }

    protected fun account(gate: CompletableDeferred<Unit>? = null, context: String = BANK): AggregateModel<Account> =
        aggregate<Account>(context, "account") {
            initial { Account() }
            decideSuspending<Deposit>(Creation.ALLOWED) { _, body ->
                gate?.await()
                Decision.events(Deposited(body.amount))
            }
            evolve<Deposited> { balance += it.amount }
        }

    protected fun deposit(id: String) = ServerCommandRequest(
        context = BANK,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":1}"""),
        caller = Caller("t"),
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    protected fun ComponentHealth.status(vararg path: String): HealthStatus =
        path.fold(this) { component, name -> checkNotNull(component.component(name)) { "No $name in $component" } }
            .status

    /** An event log that is down, every call failing as the backend says an unreachable store fails, while [down]. */
    class FlakyLog(private val log: EventLog) : EventLog by log {
        @Volatile
        var down = false

        /** While not null, the reads wait for it, as a store that does not answer. */
        @Volatile
        var hang: CompletableDeferred<Unit>? = null

        private suspend fun check() {
            hang?.await()
            if (down) {
                throw StorageUnavailableException("The log is down.")
            }
        }

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
            hang?.await()
            return if (down) {
                AppendOutcome.Unknown(
                    StorageUnavailableException("down")
                )
            } else {
                log.append(stream, transport)
            }
        }

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> =
            streams.map { append(it.stream, it.transport) }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int) = kotlinx.coroutines.flow.flow {
            check()
            log.read(ref, fromVersion, toVersion).collect { emit(it) }
        }

        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int) =
            kotlinx.coroutines.flow.flow<LogEntry> {
                check()
                log.readEntries(ref, fromVersion, toVersion).collect { emit(it) }
            }

        override suspend fun head(partition: Int): Long? {
            check()
            return log.head(partition)
        }

        override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? {
            check()
            return log.findByRequestId(ref, requestId)
        }

        override suspend fun findByRequestId(
            type: me.ahoo.wow.api.aggregate.AggregateType,
            tenantId: String,
            requestId: String,
        ): EventStreamRecord? {
            check()
            return log.findByRequestId(type, tenantId, requestId)
        }
    }

    /**
     * A lease store whose every call fails while [down], whose renewals renew nothing while [losing], and whose
     * acquisitions complete [asked], then wait for [acquiring].
     */
    class FlakyLeases(private val leases: LeaseStore) : LeaseStore {
        @Volatile
        var down = false

        @Volatile
        var losing = false

        @Volatile
        var acquiring: CompletableDeferred<Unit>? = null

        @Volatile
        var asked: CompletableDeferred<Unit>? = null

        private fun check() {
            if (down) {
                throw StorageUnavailableException("The lease store is down.")
            }
        }

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            check()
            asked?.complete(Unit)
            acquiring?.await()
            return leases.acquire(group, partition, owner, ttl)
        }

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> {
            check()
            return if (losing) emptyList() else leases.renew(group, partitions, owner, ttl)
        }

        override suspend fun release(group: String, partition: Int, owner: String): Boolean {
            check()
            return leases.release(group, partition, owner)
        }

        override suspend fun owners(group: String): List<Lease> {
            check()
            return leases.owners(group)
        }

        override suspend fun heartbeat(
            group: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            check()
            return leases.heartbeat(group, node, address, ttl, groups)
        }

        override suspend fun members(group: String): List<Member> {
            check()
            return leases.members(group)
        }

        override suspend fun leave(group: String, node: String) {
            check()
            leases.leave(group, node)
        }
    }

    protected companion object {
        const val BANK = "bank"
        const val SHOP = "shop"
        const val PARTITIONS = 4

        /** How long a test holds a dispatcher's or the drivers' only thread: four times its stall timeout. */
        const val HOLD_MILLIS = 400L

        /** How long the slow store takes to answer an acquisition: above 250 ms, far inside its stall timeout. */
        val SLOW_STORE: Duration = 1.seconds

        /** A store is down at its first failure, so a test need not wait. */
        val OPTIONS = HealthOptions(storeDownAfter = Duration.ZERO)
    }
}
