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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.subscription.HandledEntry
import me.ahoo.wow.runtime.subscription.RecordingCheckpoints
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.runtime.wait.correlationId
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

data class Deposit(val amount: Int = 1)
data class Deposited(val amount: Int)

class Account {
    var balance = 0
}

const val SERVICE = "bank-service"
const val CONTEXT = "bank"
const val PROJECTOR = "bank.projector"
const val SAGA = "bank.saga"

/** The result a deposit's decide function puts (decision D49): the node that decided it. */
const val DECIDED_BY = "decidedBy"

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
 * A lease clock that never advances ([TestCluster]'s `leaseClock`): no lease or membership expires. With the wall
 * clock, a stall of the JVM longer than `leaseTtl - renewInterval` (1.2 s in [fastOptions]), such as a pause while it
 * is cold or the machine is loaded, may expire the leases and memberships, and past the TTL (1.5 s) it expires them
 * all. Until the nodes renew and read the routes again the partitions have no known owner, and a command routed
 * meanwhile runs locally (decision D71), not on its owner.
 */
val FROZEN_LEASES: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

/** The Manifest of [TestNode]'s model: the account aggregate, its deposits, and every lifecycle command. */
fun accountManifest(service: String? = SERVICE, context: String = CONTEXT): Manifest = Manifest(
    service = service?.let(::ServiceInfo),
    contexts = listOf(
        ContextManifest(
            context,
            aggregates = listOf(
                AggregateManifest(
                    "account",
                    state = StateManifest(Manifest.schemaRef("Account")),
                    commands = listOf(
                        CommandManifest(
                            "Deposit",
                            schema = Manifest.schemaRef("Deposit"),
                            creation = ManifestCreation.ALLOWED,
                        ),
                    ),
                    events = listOf(EventManifest("Deposited", schema = Manifest.schemaRef("Deposited"))),
                    lifecycle = Lifecycle.COMMANDS.keys.toList(),
                ),
            ),
        ),
    ),
)

/**
 * Nodes of one service in one JVM: they share an in-memory log, lease store, checkpoint store and failure ledger,
 * reach each other through a [LoopbackClusterChannel], and signal waits straight to each other's registries.
 *
 * [events] records, in order, the commits to the log (`append <aggregate id>`), the checkpoint saves
 * (`checkpoint <partition> <position>`) and the lease releases (`release <partition> <node>`).
 *
 * With [snapshots], every node also runs the built-in snapshot subscription, writing to that shared store.
 *
 * With [saga], every node also runs the saga [SAGA]: for a deposit into an account listed in [derives], it deposits
 * into that many other accounts (`<id>-d-<n>`), executed where the saga runs, passing the wait context on.
 *
 * The lease store reads [leaseClock]. The default is the wall clock, so leases and memberships expire, as a test of
 * failover needs. A test of where a command runs passes [FROZEN_LEASES]: then nothing expires, whatever stalls the
 * JVM, and the nodes still converge, since a released lease is free at once and a leaving member is removed.
 */
class TestCluster(
    val partitions: Int = 16,
    val options: ClusterOptions = fastOptions(),
    val snapshots: SnapshotStore? = null,
    val saga: Boolean = false,
    leaseClock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    /** How many deposits the saga derives from a deposit into each account; none for an account not listed. */
    val derives = ConcurrentHashMap<String, Int>()
    val events = CopyOnWriteArrayList<String>()
    val log = RecordingLog(InMemoryEventLog(partitions), events)
    val leases = RecordingLeases(InMemoryLeaseStore(leaseClock), events)
    val checkpoints = RecordingCheckpoints(EventCheckpoints(events))
    val ledger = InMemoryFailureLedger()
    val backends = ContextBackends(log, checkpoints, leases, ledger, snapshots)
    val group = LeaseGroup(SERVICE, CONTEXT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val latencies = ConcurrentHashMap<String, Duration>()
    val loopback = LoopbackClusterChannel(scope) { address -> latencies[address]?.let { delay(it) } }

    /** How long each [ClusterChannel.connect] to an address takes, when listed. */
    val connectLatencies = ConcurrentHashMap<String, Duration>()

    /** The addresses each [ClusterChannel.connect] was asked for, in order. */
    val connects = CopyOnWriteArrayList<String>()

    /** The addresses whose connections fail. */
    val unreachable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The [loopback] channel, connecting to an address in its [connectLatencies], or failing when [unreachable]. */
    val channel = object : ClusterChannel {
        override suspend fun forward(address: String, request: ForwardCommand): ForwardReply =
            loopback.forward(address, request)

        override suspend fun connect(address: String) {
            connects += address
            connectLatencies[address]?.let { delay(it) }
            if (address in unreachable) {
                throw ClusterChannelException("Connection refused: $address")
            }
        }
    }
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

/**
 * One node of a [TestCluster]: a [ServiceNode] over the cluster's shared stores, with its own kernel, subscriptions,
 * ownership, router and coordinator. Appends go straight to the log and no state is cached, so every command reads
 * the log as it is.
 */
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
            putResult(DECIDED_BY, name)
            Decision.events(Deposited(body.amount))
        }
        evolve<Deposited> { balance += it.amount }
    }

    /** What this node's projector handled: (aggregate id, version). */
    val projected = CopyOnWriteArrayList<Pair<String, Int>>()

    /** The saga of [TestCluster.saga]: the derived deposits run on this node, carrying the entry's wait context on. */
    private fun saga(coordinator: WaitCoordinator): Subscription =
        Subscription.saga(SAGA, SubscriptionFilter(CONTEXT)) { entry ->
            val wait = entry.transport?.wait?.derived()
            val commands = (1..(cluster.derives[entry.ref.id] ?: 0)).map { n ->
                val id = "${entry.stream.commandId}-d-$n"
                Command(
                    id,
                    "r-$id",
                    ref("${entry.ref.id}-d-$n"),
                    Deposit(),
                    causation = Causation(entry.stream.correlationId, "${entry.ref}@${entry.version}"),
                )
            }
            commands.forEach { coordinator.forward(model, it, wait) }
            HandledEntry(commands.map { it.id })
        }

    /** Checkpoints are saved only when a partition stops, so a save shows the hand-off. */
    val service = ServiceNode(
        SERVICE,
        name,
        address,
        listOf(model),
        accountManifest(),
        { cluster.backends },
        cluster.channel,
        remoteSignals = { origin, signal -> cluster.nodes[origin.node]?.registry?.signal(signal) },
        options = ServiceNodeOptions(
            cluster = cluster.options,
            subscription = testOptions(checkpointInterval = 1.hours),
            appendQueue = AppendQueueOptions(enabled = false),
            stateCacheCapacity = 0,
        ),
        subscriptions = { context ->
            listOfNotNull(
                Subscription(PROJECTOR, SubscriptionFilter(CONTEXT)) { projected += it.ref.id to it.version },
                if (cluster.saga) saga(context.coordinator) else null,
            )
        },
    )

    val registry: WaitRegistry get() = service.waits
    val ownership: PartitionOwnership get() = service.context(CONTEXT).ownership
    val coordinator: WaitCoordinator get() = service.context(CONTEXT).coordinator

    suspend fun start() {
        cluster.loopback.bind(address, service.forwardHandler)
        service.start()
    }

    /** Leaves gracefully (the node's whole stop), then stops serving the cluster channel. */
    suspend fun leave() {
        service.stop()
        cluster.loopback.unbind(address)
    }

    /** Dies at once: unreachable, and its membership and leases left to expire. */
    suspend fun kill() {
        cluster.loopback.unbind(address)
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
        fun ref(id: String) = AggregateType(CONTEXT, "account").ref("t", id)
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
    private val delegate: CheckpointStore = me.ahoo.wow.kernel.memory.InMemoryCheckpointStore(),
) : CheckpointStore by delegate {
    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        delegate.save(group, subscription, partition, position)
        events += "checkpoint $partition $position"
    }

    override suspend fun saveAll(group: String, checkpoints: List<me.ahoo.wow.spi.subscription.Checkpoint>) {
        checkpoints.forEach { save(group, it.subscription, it.partition, it.position) }
    }
}
