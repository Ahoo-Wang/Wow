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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.subscription.RecordingCheckpoints
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.wait.Origin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class Deposit(val amount: Int = 1)
data class Deposited(val amount: Int)

class Account {
    var balance = 0
}

const val SERVICE = "bank-service"
const val CONTEXT = "bank"
const val PROJECTOR = "bank.projector"

/** Cluster timings short enough for tests, keeping the ratios of the defaults. */
fun fastOptions(
    forwardTimeout: Duration = 1.seconds,
    maxInFlightPerPeer: Int = 1_024,
): ClusterOptions = ClusterOptions(
    leaseTtl = 1_500.milliseconds,
    renewInterval = 300.milliseconds,
    memberTtl = 1_500.milliseconds,
    heartbeatInterval = 300.milliseconds,
    reconcileInterval = 100.milliseconds,
    routeRefreshInterval = 100.milliseconds,
    forwardTimeout = forwardTimeout,
    maxInFlightPerPeer = maxInFlightPerPeer,
    drainTimeout = 5.seconds,
)

/**
 * Nodes of one service in one JVM: they share an in-memory log, lease store, checkpoint store and failure ledger,
 * reach each other through a [LoopbackClusterChannel], and signal waits straight to each other's registries.
 *
 * [events] records, in order, the commits to the log (`append <aggregate id>`), the checkpoint saves
 * (`checkpoint <partition> <position>`) and the lease releases (`release <partition> <node>`).
 *
 * With [snapshots], every node also runs the built-in snapshot subscription, writing to that shared store.
 */
class TestCluster(
    val partitions: Int = 16,
    val options: ClusterOptions = fastOptions(),
    val snapshots: SnapshotStore? = null,
) : AutoCloseable {
    val events = CopyOnWriteArrayList<String>()
    val log = RecordingLog(InMemoryEventLog(partitions), events)
    val leases = RecordingLeases(InMemoryLeaseStore(), events)
    val checkpoints = RecordingCheckpoints(EventCheckpoints(events))
    val ledger = InMemoryFailureLedger()
    val group = LeaseGroup(SERVICE, CONTEXT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val latencies = ConcurrentHashMap<String, Duration>()
    val channel = LoopbackClusterChannel(scope) { address -> latencies[address]?.let { delay(it) } }
    val nodes = ConcurrentHashMap<String, TestNode>()
    private val sequence = AtomicInteger()

    fun node(name: String): TestNode = nodes.computeIfAbsent(name) { TestNode(it, this) }

    suspend fun start(vararg names: String): List<TestNode> = names.map { node(it).also { node -> node.start() } }

    /** The nodes taking part now. */
    val running: List<TestNode> get() = nodes.values.filter { it.ownership.isRunning }

    /** An aggregate id in [partition], the [skip]th one. */
    fun idIn(partition: Int, skip: Int = 0): String = generateSequence(0) { it + 1 }.map { "acc-$it" }
        .filter { partitionOf(it) == partition }.drop(skip).first()

    fun partitionOf(id: String): Int = Partitioner.partitionOf(TestNode.ref(id), partitions)

    /** The lease holders of each partition, from the store. */
    suspend fun owners(): Map<Int, String> = leases.owners(group.name).associate { it.partition to it.owner }

    /**
     * Waits until every running node holds exactly its HRW share among the running nodes, and has read the routes
     * that say so.
     */
    suspend fun awaitStable(timeout: Duration = 15.seconds) {
        eventually(timeout) {
            val names = running.map { it.name }
            val expected = Hrw.assignment(names, partitions)
            owners() == expected &&
                running.all { node ->
                    node.ownership.owned == Hrw.partitionsOf(node.name, names, partitions) &&
                        node.ownership.routes.owners.mapValues { it.value.node } == expected
                }
        }
    }

    fun nextId(): String = "c-${sequence.incrementAndGet()}"

    override fun close() {
        scope.cancel()
    }
}

/** One node of a [TestCluster]: its own kernel, state cache, subscriptions, ownership, router and coordinator. */
class TestNode(val name: String, private val cluster: TestCluster) {
    val address = "$name.internal:7070"

    /** The commands this node's decide function ran, by command id. */
    val decided = CopyOnWriteArrayList<String>()

    /** While not null, this node's decide function waits for it. */
    @Volatile
    var hold: CompletableDeferred<Unit>? = null

    val model = aggregate<Account>(CONTEXT, "account") {
        initial { Account() }
        decideSuspending<Deposit>(Creation.ALLOWED) { _, body ->
            decided += command.id
            hold?.await()
            Decision.events(Deposited(body.amount))
        }
        evolve<Deposited> { balance += it.amount }
    }

    /** What this node's projector handled: (aggregate id, version). */
    val projected = CopyOnWriteArrayList<Pair<String, Int>>()

    val registry = WaitRegistry()
    private val signals = LocalSignalSender(name, registry) { origin, signal ->
        cluster.nodes[origin.node]?.registry?.signal(signal)
    }

    private val kernel = Kernel(cluster.log, snapshots = cluster.snapshots)

    /** The built-in snapshot subscription, when the cluster has a snapshot store. */
    val snapshotting = cluster.snapshots?.let { SnapshotSubscription(CONTEXT, listOf(model), it, kernel, cluster.log) }

    /** Checkpoints are saved only when a partition stops, so a save shows the hand-off. */
    val subscriptions = SubscriptionRuntime(
        cluster.group.name,
        cluster.log,
        cluster.checkpoints,
        cluster.ledger,
        listOfNotNull(
            Subscription(PROJECTOR, SubscriptionFilter(CONTEXT)) { projected += it.ref.id to it.version },
            snapshotting?.subscription,
        ),
        testOptions(checkpointInterval = 1.hours),
        signals = signals,
    )
    val ownership = PartitionOwnership(
        cluster.group,
        name,
        address,
        cluster.partitions,
        cluster.leases,
        cluster.options,
        subscriptions,
    )
    val router = PartitionedRouter(listOf(ownership), cluster.channel, cluster.options)
    val coordinator = WaitCoordinator(
        Executor(kernel, views = snapshotting?.views),
        registry,
        subscriptions,
        signals,
        WaitOptions(Origin(name, address, SERVICE)),
        router = router,
    )
    private val endpoint = ForwardEndpoint(coordinator, listOf(model), listOf(ownership))

    suspend fun start() {
        cluster.channel.bind(address, endpoint)
        ownership.start()
    }

    /** Leaves gracefully, then stops serving the cluster channel. */
    suspend fun leave() {
        ownership.stop()
        cluster.channel.unbind(address)
    }

    /** Dies at once: unreachable, and its membership and leases left to expire. */
    suspend fun kill() {
        cluster.channel.unbind(address)
        ownership.abort()
    }

    /** Deposits into account [id] through this node, waiting as [wait] says. */
    suspend fun deposit(
        id: String,
        commandId: String = cluster.nextId(),
        wait: WaitPlan = WaitPlan.COMMITTED,
    ): CommandResult = coordinator.execute(model, command(id, commandId, wait))

    fun command(id: String, commandId: String = cluster.nextId(), wait: WaitPlan = WaitPlan.COMMITTED) =
        Command(commandId, "r-$commandId", ref(id), Deposit(), wait = wait)

    companion object {
        fun ref(id: String) = me.ahoo.wow.api.aggregate.AggregateType(CONTEXT, "account").ref("t", id)
    }
}

/** An [EventLog] that records each commit as `append <aggregate id>`. */
class RecordingLog(private val delegate: EventLog, private val events: MutableList<String>) : EventLog by delegate {
    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        delegate.append(stream, transport).also {
            if (it is AppendOutcome.Committed) {
                events += "append ${stream.ref.id}"
            }
        }
}

/** A [LeaseStore] that records each release as `release <partition> <node>`. */
class RecordingLeases(private val delegate: LeaseStore, private val events: MutableList<String>) :
    LeaseStore by delegate {
    override suspend fun release(group: String, partition: Int, owner: String): Boolean =
        delegate.release(group, partition, owner).also {
            if (it) {
                events += "release $partition $owner"
            }
        }
}

/** A [CheckpointStore] that records each save as `checkpoint <partition> <position>`. */
class EventCheckpoints(
    private val events: MutableList<String>,
    private val delegate: CheckpointStore = me.ahoo.wow.kernel.InMemoryCheckpointStore(),
) : CheckpointStore by delegate {
    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        delegate.save(group, subscription, partition, position)
        events += "checkpoint $partition $position"
    }
}
