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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The node's health (review R3.5-3): the readiness follows the node's state, its lease store, its event log and its
 * topics, and recovers with them; the liveness ignores a storage outage and fails only when the node is stuck.
 */
class NodeHealthTest {
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val healths = CopyOnWriteArrayList<NodeHealth>()
    private val log = FlakyLog(InMemoryEventLog(PARTITIONS))
    private val leases = FlakyLeases(InMemoryLeaseStore())

    /** What a slow subscription waits for. */
    private val slow = CompletableDeferred<Unit>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        log.hang?.complete(Unit)
        log.hang = null
        slow.complete(Unit)
        healths.forEach { it.close() }
        nodes.forEach { it.stop() }
    }

    private val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = leases,
            ledger = InMemoryFailureLedger(),
            snapshots = InMemorySnapshotStore(),
        )
    }

    private fun node(
        model: AggregateModel<Account> = account(),
        options: ServiceNodeOptions = fastNodeOptions(),
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
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

    private fun health(node: ServiceNode, options: HealthOptions = OPTIONS): NodeHealth =
        NodeHealth(node, options).also { healths += it }

    private fun account(gate: CompletableDeferred<Unit>? = null): AggregateModel<Account> =
        aggregate<Account>(BANK, "account") {
            initial { Account() }
            decideSuspending<Deposit>(Creation.ALLOWED) { _, body ->
                gate?.await()
                Decision.events(Deposited(body.amount))
            }
            evolve<Deposited> { balance += it.amount }
        }

    private fun deposit(id: String) = CommandRequest(
        context = BANK,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":1}"""),
        caller = Caller("t"),
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    private fun ComponentHealth.status(vararg path: String): HealthStatus =
        path.fold(this) { component, name -> checkNotNull(component.component(name)) { "No $name in $component" } }
            .status

    @Test
    fun `a running node should be ready and live, and not ready before it starts or once it stops`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node)
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().status(NodeHealth.NODE).assert().isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)

            node.start()
            health.start()
            health.start()
            health.isRunning.assert().isTrue()
            eventually { health.readiness().status == HealthStatus.UP }
            node.gateway.execute(deposit("a-1"))
            val snapshotsOf = { health: ComponentHealth ->
                health.component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                    .component(me.ahoo.wow.api.result.Hop.SNAPSHOT_SUBSCRIPTION)!!
            }
            eventually {
                health.sample()
                snapshotsOf(health.health()).details["lag"] == 0L
            }
            val all = health.health()
            all.name.assert().isEqualTo(NodeHealth.WOW)
            all.status.assert().isEqualTo(HealthStatus.UP)
            all.components.map { it.name }.assert().containsExactly(
                NodeHealth.NODE,
                NodeHealth.OWNERSHIP,
                NodeHealth.STORES,
                NodeHealth.TOPICS,
                NodeHealth.SUBSCRIPTIONS,
                NodeHealth.EXECUTOR,
                NodeHealth.LIVENESS,
            )
            val group = all.component(NodeHealth.OWNERSHIP)!!.components.single()
            group.name.assert().isEqualTo("$NODE_SERVICE/$BANK")
            group.details["owned"].assert().isEqualTo(PARTITIONS)
            group.details["share"].assert().isEqualTo(PARTITIONS)
            group.details["handover"].assert().isEqualTo(false)
            val store = all.component(NodeHealth.STORES)!!.components.single()
            store.details["kind"].assert().isEqualTo(StoreActivity.LOG)
            store.details["failures"].assert().isEqualTo(0)
            all.component(NodeHealth.EXECUTOR)!!.details["maxInFlight"].assert().isEqualTo(4_096)
            // The snapshot subscription has caught up with the deposit.
            val snapshots = snapshotsOf(all)
            snapshots.details["lag"].assert().isEqualTo(0L)
            snapshots.details["parked"].assert().isEqualTo(0)
            all.component(NodeHealth.SUBSCRIPTIONS)!!.details.keys.assert().contains("sampledMillis")

            node.stop()
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            health.close()
            health.isRunning.assert().isFalse()
        }

    @Test
    fun `an unreachable lease store should make the node not ready until it answers again`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start()
        health.sample()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)

        leases.down = true
        health.sample()
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.DOWN)
        readiness.status(NodeHealth.OWNERSHIP).assert().isEqualTo(HealthStatus.DOWN)
        val group = readiness.component(NodeHealth.OWNERSHIP)!!.components.single()

        @Suppress("UNCHECKED_CAST")
        val leaseStore = group.details[NodeHealth.LEASE_STORE] as Map<String, Any?>
        leaseStore["status"].assert().isEqualTo("DOWN")
        leaseStore["lastError"].assert().isEqualTo("StorageUnavailableException")
        // The stores of the log answer: the liveness does not restart a node whose lease store is away.
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)

        leases.down = false
        health.sample()
        // Failed lately, but answers again: degraded, and ready.
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        health.health().status(NodeHealth.OWNERSHIP).assert().isEqualTo(HealthStatus.DEGRADED)
    }

    @Test
    fun `a store that is down should make the node not ready, and leave it live`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start()
        health.start()
        eventually { health.readiness().status == HealthStatus.UP }

        log.down = true
        health.sample()
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.DOWN)
        readiness.status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DOWN)
        val store = readiness.component(NodeHealth.STORES)!!.components.single()
        store.details["lastError"].assert().isEqualTo("StorageUnavailableException")
        store.details.keys.assert().contains("failingMillis")
        // A command fails with the store unavailable, and the kernel goes on: no restart storm.
        node.gateway.execute(deposit("a-1")).status.assert().isEqualTo(CommandResult.Status.FAILED)
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)

        log.down = false
        health.sample()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        health.health().status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DEGRADED)
        // Once the window of the failures has passed, the store is up again.
        delay(5)
        NodeHealth(node, HealthOptions(storeDownAfter = Duration.ZERO, storeWindow = 1.milliseconds)).health()
            .status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.UP)
    }

    @Test
    fun `a store that does not answer should be down, and a kernel stuck on it should stay live`(): Unit =
        runBlocking {
            val node = node()
            val health = health(
                node,
                HealthOptions(
                    sampleInterval = 50.milliseconds,
                    storeDownAfter = 100.milliseconds,
                    stallTimeout = 100.milliseconds,
                ),
            )
            node.start()
            health.start()
            eventually { health.readiness().status == HealthStatus.UP }

            log.hang = CompletableDeferred()
            coroutineScope {
                val stuck = async { node.gateway.execute(deposit("a-1")) }
                eventually { node.context(BANK).executor.commandsInFlight == 1 }
                eventually { health.readiness().status == HealthStatus.DOWN }
                health.readiness().component(NodeHealth.STORES)!!.components.single().details.keys.assert()
                    .contains("probingMillis")
                delay(200)
                // In flight with no progress, but the store does not answer: the kernel waits for it.
                health.liveness().status.assert().isEqualTo(HealthStatus.UP)
                log.hang!!.complete(Unit)
                stuck.await()
            }
            log.hang = null
            eventually { health.readiness().status == HealthStatus.UP }
        }

    @Test
    fun `with down-after zero a store should be down at its first failure, never while a probe is under way`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO))
            node.start()
            eventually { health.readiness().status == HealthStatus.UP }

            // In flight: a probe that has not answered yet has not failed.
            log.hang = CompletableDeferred()
            coroutineScope {
                val probe = launch { health.sample() }
                eventually {
                    health.readiness().component(
                        NodeHealth.STORES
                    )!!.components.single().details.containsKey("probingMillis")
                }
                health.readiness().status.assert().isEqualTo(HealthStatus.UP)
                health.readiness().status(NodeHealth.STORES).assert().isNotEqualTo(HealthStatus.DOWN)

                // Timed out (after its timeout, a second at least): a failure, so down at once.
                probe.join()
                val timedOut = health.readiness()
                timedOut.status.assert().isEqualTo(HealthStatus.DOWN)
                timedOut.component(NodeHealth.STORES)!!.components.single().details["lastError"]
                    .assert().isEqualTo("TimeoutException")
            }
            log.hang!!.complete(Unit)
            log.hang = null

            // Recovers at its next success.
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)

            // Failed: down at the first failure.
            log.down = true
            health.sample()
            health.readiness().status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DOWN)
            log.down = false
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        }

    @Test
    fun `a kernel stuck while the stores answer should fail the liveness`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val node = node(account(gate), fastNodeOptions().copyWith(maxInFlight = 1))
        val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO, stallTimeout = 100.milliseconds))
        node.start()
        health.start()
        coroutineScope {
            val stuck = async { node.gateway.execute(deposit("a-1")) }
            eventually { node.context(BANK).executor.commandsInFlight == 1 }
            // One command in flight fills a bound of one.
            health.health().status(NodeHealth.EXECUTOR).assert().isEqualTo(HealthStatus.DEGRADED)
            delay(150)
            health.sample()
            val liveness = health.liveness()
            liveness.status.assert().isEqualTo(HealthStatus.DOWN)
            liveness.details["stalled"].assert().isEqualTo("kernel")
            gate.complete(Unit)
            stuck.await()
        }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
    }

    @Test
    fun `a dispatcher that does not tick should fail the liveness`(): Unit = runBlocking {
        val node = node()
        node.start()
        val single = Executors.newSingleThreadExecutor()
        try {
            val health = NodeHealth(
                node,
                HealthOptions(stallTimeout = 100.milliseconds),
                single.asCoroutineDispatcher()
            )
            healths += health
            health.start()
            delay(50)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            single.execute { Thread.sleep(400) }
            eventually { health.liveness().status == HealthStatus.DOWN }
            health.liveness().details["stalled"].assert().isEqualTo("dispatcher")
            eventually { health.liveness().status == HealthStatus.UP }
        } finally {
            single.shutdownNow()
        }
    }

    @Test
    fun `an ownership that keeps none of its share past the grace should be down`(): Unit = runBlocking {
        val node = node()
        val health = health(node, HealthOptions(handoverGrace = Duration.ZERO, storeDownAfter = 1.minutes))
        node.start()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        // The lease store stops renewing: the leases go overdue and the partitions stop here.
        leases.down = true
        eventually { node.context(BANK).ownership.owned.isEmpty() }
        val ownership = health.readiness().component(NodeHealth.OWNERSHIP)!!
        ownership.status.assert().isEqualTo(HealthStatus.DOWN)
        ownership.components.single().details["handover"].assert().isEqualTo(true)
        leases.down = false
        eventually { health.readiness().status == HealthStatus.UP }
    }

    @Test
    fun `a subscription behind, parking or paused should be degraded, and leave the node ready`(): Unit =
        runBlocking {
            val node = node(
                subscriptions = {
                    listOf(
                        Subscription("Slow.onDeposit", SubscriptionFilter(BANK)) { slow.await() },
                        Subscription("Failing.onDeposit", SubscriptionFilter(BANK)) { error("Always fails.") },
                    )
                },
            )
            val paused = PausedPartition("g", "Slow.onDeposit", 1, 1_000, "StorageUnavailableException")
            val health = NodeHealth(
                node,
                HealthOptions(storeDownAfter = Duration.ZERO, lagDegradedAt = 1, parkedDegradedAt = 5),
                pausedOf = { listOf(paused) },
                millis = { 3_000 },
            ).also { healths += it }
            node.start()
            // A first sample, before anything parks: the parked count grows from it.
            health.sample()
            node.gateway.execute(deposit("a-1"))
            eventually {
                health.sample()
                health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                    .component("Failing.onDeposit")!!.details["parked"] == 1
            }
            val group = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
            group.status.assert().isEqualTo(HealthStatus.DEGRADED)
            val failing = group.component("Failing.onDeposit")!!
            failing.status.assert().isEqualTo(HealthStatus.DEGRADED)
            failing.details["parkedGrowing"].assert().isEqualTo(true)
            val behind = group.component("Slow.onDeposit")!!
            behind.status.assert().isEqualTo(HealthStatus.DEGRADED)
            (behind.details["lag"] as Long).assert().isGreaterThanOrEqualTo(1L)
            behind.details["paused"].assert().isEqualTo(
                listOf(mapOf("partition" to 1, "pausedMillis" to 2_000L, "reason" to "StorageUnavailableException")),
            )
            // Subscriptions never take the node out of service.
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            slow.complete(Unit)
        }

    @Test
    fun `a partition the runtime paused on a systemic failure should degrade its subscription only (D243)`(): Unit =
        runBlocking {
            val node = node(
                subscriptions = {
                    listOf(
                        Subscription("Mirror.onDeposit", SubscriptionFilter(BANK)) {
                            throw StorageUnavailableException("The mirror's store is down.")
                        },
                    )
                },
            )
            val health = health(node)
            node.start()
            node.gateway.execute(deposit("a-1"))
            eventually { node.context(BANK).subscriptions.paused().isNotEmpty() }
            health.sample()
            val mirror = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                .component("Mirror.onDeposit")!!
            mirror.status.assert().isEqualTo(HealthStatus.DEGRADED)
            @Suppress("UNCHECKED_CAST")
            val paused = (mirror.details["paused"] as List<Map<String, Any?>>).single()
            paused["partition"].assert().isEqualTo(node.context(BANK).subscriptions.paused().single().partition)
            (paused["reason"] as String).assert().isNotBlank()
            // A pause waits for what it depends on: the node stays ready and live.
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
        }

    @Test
    fun `options should refuse thresholds out of their range`() {
        HealthOptions().toString().assert().contains("storeDownAfter=15s")
        listOf(
            { HealthOptions(sampleInterval = Duration.ZERO) },
            { HealthOptions(storeDownAfter = (-1).milliseconds) },
            { HealthOptions(storeWindow = Duration.ZERO) },
            { HealthOptions(handoverGrace = (-1).milliseconds) },
            { HealthOptions(executorSaturation = 0.0) },
            { HealthOptions(executorSaturation = 1.5) },
            { HealthOptions(lagDegradedAt = 0) },
            { HealthOptions(parkedDegradedAt = 0) },
            { HealthOptions(stallTimeout = Duration.ZERO) },
        ).forEach { options ->
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> { options() }
        }
    }

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

        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
            hang?.await()
            return if (down) {
                AppendOutcome.Unknown(
                    StorageUnavailableException("down")
                )
            } else {
                log.append(stream, transport)
            }
        }

        override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> =
            streams.map { (stream, transport) -> append(stream, transport) }

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

        override suspend fun findByRequestId(
            type: me.ahoo.wow.api.aggregate.AggregateType,
            tenantId: String,
            requestId: String,
        ): EventStreamRecord? {
            check()
            return log.findByRequestId(type, tenantId, requestId)
        }
    }

    /** A lease store whose every call fails while [down]. */
    class FlakyLeases(private val leases: LeaseStore) : LeaseStore {
        @Volatile
        var down = false

        private fun check() {
            if (down) {
                throw StorageUnavailableException("The lease store is down.")
            }
        }

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            check()
            return leases.acquire(group, partition, owner, ttl)
        }

        override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            check()
            return leases.renew(group, partition, owner, ttl)
        }

        override suspend fun release(group: String, partition: Int, owner: String): Boolean {
            check()
            return leases.release(group, partition, owner)
        }

        override suspend fun owners(group: String): List<Lease> {
            check()
            return leases.owners(group)
        }

        override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member {
            check()
            return leases.heartbeat(group, node, address, ttl)
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

    private companion object {
        const val BANK = "bank"
        const val PARTITIONS = 4

        /** A store is down at its first failure, so a test need not wait. */
        val OPTIONS = HealthOptions(storeDownAfter = Duration.ZERO)
    }
}
