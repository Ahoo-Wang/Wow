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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.clockOf
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.PlacedStores
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
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
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The node's health (review R3.5-3): the readiness follows the node's state, its lease store, its event log and its
 * topics, and recovers with them; the liveness ignores a storage outage and fails only when the node is stuck.
 */
class NodeHealthTest {
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val healths = CopyOnWriteArrayList<NodeHealth>()
    private val samplers = HashMap<NodeHealth, NodeSampler>()
    private val log = FlakyLog(InMemoryEventLog(PARTITIONS))
    private val leases = FlakyLeases(InMemoryLeaseStore())

    /** What a slow subscription waits for. */
    private val slow = CompletableDeferred<Unit>()

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

    private val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = leases,
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
        )
    }

    /** A store instance whose probe fails while [down], counting how often it is closed. */
    private class ProbedStore(name: String, type: String) : Store(StoreConfig.of(name, type)) {
        @Volatile
        var down = false
        var closes = 0
        var failsToClose = false
        override val location: Any = Any()

        override suspend fun probe() = check(!down) { "$name is down." }

        override fun closeStore() {
            closes++
            check(!failsToClose) { "$name fails to close." }
        }

        override fun openCheckpoints(): Opened<CheckpointStore> = error("unused")

        override fun openLeases(): Opened<LeaseStore> = error("unused")

        override fun openLedger(): Opened<FailureLedger> = error("unused")

        override fun openReservations(): Opened<ReservationStore> = error("unused")

        override fun openLog(context: String, partitions: Int): Opened<EventLog> = error("unused")

        override fun openSnapshots(context: String): Opened<SnapshotStore> = error("unused")

        override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = error("unused")
    }

    private fun node(
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

    private fun health(node: ServiceNode, options: HealthOptions = OPTIONS): NodeHealth =
        healthOf(node, options) { sampler -> NodeHealth(node, sampler, options) }

    /** The health [create]d over a sampler of [node] as a host makes one, its reads bounded as a probe is. */
    private fun healthOf(node: ServiceNode, options: HealthOptions, create: (NodeSampler) -> NodeHealth): NodeHealth {
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

    /** Starts [health]'s tick and its sampler, as a host starts both. */
    private fun start(health: NodeHealth) {
        health.start()
        samplers.getValue(health).start()
    }

    private fun account(gate: CompletableDeferred<Unit>? = null, context: String = BANK): AggregateModel<Account> =
        aggregate<Account>(context, "account") {
            initial { Account() }
            decideSuspending<Deposit>(Creation.ALLOWED) { _, body ->
                gate?.await()
                Decision.events(Deposited(body.amount))
            }
            evolve<Deposited> { balance += it.amount }
        }

    private fun deposit(id: String) = ServerCommandRequest(
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
            start(health)
            start(health)
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
                NodeHealth.SECONDARY_STORES,
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
    fun `only a store the write path needs should make the node not ready, naming it, and any other only be shown`(): Unit =
        runBlocking {
            val logs = ProbedStore("main", "postgres")
            val search = ProbedStore("search", "elasticsearch").apply { failsToClose = true }
            val node = node(
                backends = BackendFactory {
                    backends.backends(it).copyWith(stores = PlacedStores(setOf(logs), setOf(search)))
                },
            )
            val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO))
            node.start()
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            // An additional snapshot or search store down: the node serves its commands, and shows the store down.
            search.down = true
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            val secondary = health.health().component(NodeHealth.SECONDARY_STORES)!!
            secondary.status.assert().isEqualTo(HealthStatus.DOWN)
            secondary.component("store:search")!!.details["type"].assert().isEqualTo("elasticsearch")
            // The store of its logs down: the node is not ready, and says which store.
            search.down = false
            logs.down = true
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().component(NodeHealth.STORES)!!.component("store:main")!!.status.assert()
                .isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            node.stop()
            // Once the logs are closed, each placed store is closed once, one failing to close the others all the same,
            // and closing it again does nothing.
            listOf(logs, search).forEach { it.closes.assert().isEqualTo(1) }
            logs.close()
            logs.closes.assert().isEqualTo(1)
        }

    @Test
    fun `a node on standby should be ready, its lease group marked standby until it is resumed`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start(standby = true)
        // It serves reads: ready, though it joins no lease group until it is resumed.
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        val group = health.readiness().component(NodeHealth.OWNERSHIP)!!.components.single()
        group.status.assert().isEqualTo(HealthStatus.UP)
        group.details["standby"].assert().isEqualTo(true)
        group.details["running"].assert().isEqualTo(false)

        node.resume("operator")
        eventually { health.readiness().status == HealthStatus.UP }
        health.readiness().component(NodeHealth.OWNERSHIP)!!.components.single().details.keys.assert()
            .doesNotContain("standby", "running")
        node.stop()
        health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
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
        start(health)
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
        health(node, HealthOptions(storeDownAfter = Duration.ZERO, storeWindow = 1.milliseconds)).health()
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
            start(health)
            eventually { health.readiness().status == HealthStatus.UP }

            log.hang = CompletableDeferred()
            coroutineScope {
                val stuck = async { node.gateway.execute(deposit("a-1")) }
                eventually { node.context(BANK).executor.commandsInFlight == 1 }
                // Down once a probe timed out (after a second at least): a probe still under way is no failure.
                eventually { health.readiness().status == HealthStatus.DOWN }
                eventually {
                    health.readiness().component(NodeHealth.STORES)!!.components.single()
                        .details["lastError"] == "TimeoutException"
                }
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
        val node = node(account(gate), fastNodeOptions().copyWith(executor = ExecutorOptions(maxInFlight = 1)))
        val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO, stallTimeout = 100.milliseconds))
        node.start()
        start(health)
        coroutineScope {
            val stuck = async { node.gateway.execute(deposit("a-1")) }
            eventually { node.context(BANK).executor.commandsInFlight == 1 }
            // One command in flight fills a bound of one.
            health.health().status(NodeHealth.EXECUTOR).assert().isEqualTo(HealthStatus.DEGRADED)
            delay(150)
            health.sample()
            val liveness = health.liveness()
            liveness.status.assert().isEqualTo(HealthStatus.DOWN)
            (liveness.details["stalls"] as List<*>).assert().contains(NodeHealth.STALLED_KERNEL)
            gate.complete(Unit)
            stuck.await()
        }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
    }

    /**
     * The liveness of a running node whose dispatcher and drivers are threads of their own, each held for 400 ms by
     * [hold] (given the dispatcher's and the drivers' executors), against a stall timeout of 100 ms: the stalls it
     * listed once down with stalls that satisfy [listed], before it was up again.
     */
    private suspend fun stallsWhile(
        listed: (List<*>) -> Boolean = { true },
        hold: (ExecutorService, ExecutorService) -> Unit,
    ): List<*> {
        val node = node()
        node.start()
        val dispatcher = Executors.newSingleThreadExecutor()
        val drivers = Executors.newSingleThreadExecutor()
        try {
            val options = HealthOptions(stallTimeout = 100.milliseconds)
            val health = healthOf(node, options) {
                NodeHealth(node, it, options, dispatcher.asCoroutineDispatcher(), drivers.asCoroutineDispatcher())
            }
            start(health)
            delay(50)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            hold(dispatcher, drivers)
            var stalls: List<*> = emptyList<Any>()
            eventually {
                val liveness = health.liveness()
                stalls = liveness.details["stalls"] as? List<*> ?: emptyList<Any>()
                liveness.status == HealthStatus.DOWN && liveness.details["stalled"] == stalls.first() && listed(stalls)
            }
            eventually { health.liveness().status == HealthStatus.UP }
            health.liveness().details.containsKey("stalls").assert().isFalse()
            return stalls
        } finally {
            dispatcher.shutdownNow()
            drivers.shutdownNow()
        }
    }

    @Test
    fun `a dispatcher that does not tick should fail the liveness, the drivers ticking on`(): Unit = runBlocking {
        stallsWhile { dispatcher, _ -> dispatcher.execute { Thread.sleep(HOLD_MILLIS) } }
            .assert().containsExactly(NodeHealth.STALLED_DISPATCHER)
    }

    @Test
    fun `drivers that run nothing should fail the liveness, as a wedged virtual-thread executor does`(): Unit =
        runBlocking {
            // The one driver thread runs nothing else: no store call of the node would return either.
            stallsWhile { _, drivers -> drivers.execute { Thread.sleep(HOLD_MILLIS) } }
                .assert().containsExactly(NodeHealth.STALLED_DRIVERS)
        }

    @Test
    fun `a dispatcher and drivers stalled at once should both be listed, the dispatcher first`(): Unit = runBlocking {
        // Each held for 400 ms against 100: a liveness while both are held lists both, in its fixed order.
        stallsWhile({ it.size == 2 }) { dispatcher, drivers ->
            dispatcher.execute { Thread.sleep(HOLD_MILLIS) }
            drivers.execute { Thread.sleep(HOLD_MILLIS) }
        }.assert().containsExactly(NodeHealth.STALLED_DISPATCHER, NodeHealth.STALLED_DRIVERS)
    }

    @Test
    fun `a reconcile that does not end should fail the liveness, naming its group, until it ends`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node, HealthOptions(storeDownAfter = 1.minutes, stallTimeout = 300.milliseconds))
            node.start()
            start(health)
            val ownership = node.context(BANK).ownership
            eventually { ownership.owned.size == PARTITIONS }
            // The partitions are lost, and acquiring them again never returns: a store call on wedged drivers.
            val gate = CompletableDeferred<Unit>()
            val asked = CompletableDeferred<Unit>()
            leases.acquiring = gate
            leases.asked = asked
            leases.losing = true
            // A reconcile is acquiring a lost partition, not merely running: one that found nothing lost yet ends.
            asked.await()
            leases.losing = false
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            eventually {
                val liveness = health.liveness()
                liveness.status == HealthStatus.DOWN &&
                    NodeHealth.STALLED_RECONCILE in liveness.details["stalls"] as List<*>
            }
            val liveness = health.liveness()
            liveness.details["group"].assert().isEqualTo(ownership.group.name)
            leases.acquiring = null
            gate.complete(Unit)
            eventually { ownership.owned.size == PARTITIONS }
            eventually { health.liveness().status == HealthStatus.UP }
        }

    @Test
    fun `a reconcile waiting on a slow store that answers should leave the node live`(): Unit = runBlocking {
        val node = node()
        val health = health(node, HealthOptions(storeDownAfter = 1.minutes, stallTimeout = 5.seconds))
        node.start()
        start(health)
        val ownership = node.context(BANK).ownership
        eventually { ownership.owned.size == PARTITIONS }
        // The partitions are lost, and a reconcile acquires them again from a store that takes a while to answer.
        val answer = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        leases.acquiring = answer
        leases.asked = asked
        leases.losing = true
        asked.await()
        leases.losing = false
        // That reconcile waits on the store now, well inside the stall timeout: the node stays live throughout.
        val waiting = TimeSource.Monotonic.markNow()
        while (waiting.elapsedNow() < SLOW_STORE) {
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            delay(50)
        }
        ownership.reconcilingFor.assert().isNotNull().isGreaterThanOrEqualTo(SLOW_STORE)
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
        // The store answers: the reconcile ends with the partitions back, and the node is still live.
        leases.acquiring = null
        answer.complete(Unit)
        eventually { ownership.owned.size == PARTITIONS && ownership.reconcilingFor == null }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
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
            val options = HealthOptions(storeDownAfter = Duration.ZERO, lagDegradedAt = 1, parkedDegradedAt = 5)
            val health = healthOf(node, options) {
                NodeHealth(node, it, options, pausedOf = { listOf(paused) }, clock = clockOf { 3_000 })
            }
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
    fun `each context's snapshot subscription should show its own parked entries, never summed with another's`(): Unit =
        runBlocking {
            val ledgers = mapOf(BANK to InMemoryFailureLedger(), SHOP to InMemoryFailureLedger())
            val node = ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(account(), account(context = SHOP)),
                accountsManifest(BANK, SHOP),
                { context ->
                    ContextBackends(
                        log = InMemoryEventLog(PARTITIONS),
                        checkpoints = InMemoryCheckpointStore(),
                        leases = InMemoryLeaseStore(),
                        ledger = ledgers.getValue(context),
                        snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
                    )
                },
                channel,
                options = fastNodeOptions(),
            ).also { nodes += it }
            channel.bind(node.address, node.forwardHandler)
            ledgers.forEach { (context, ledger) ->
                ledger.park(ParkedAggregate("snapshot", AggregateType(context, "account").ref("t", "a-1"), 1, "x", 0))
            }
            val health = health(node)
            node.start()
            health.sample()
            val groups = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components
            val parked = groups.associate { group -> group.name to group.component("snapshot")?.details?.get("parked") }
            parked.values.assert().containsOnly(1)
            parked.size.assert().isEqualTo(2)
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

        override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> =
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

    private companion object {
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
