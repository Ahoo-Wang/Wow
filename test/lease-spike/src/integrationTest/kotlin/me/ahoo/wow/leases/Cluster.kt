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

// The spike runs its nodes, load and observer on the shared default dispatcher, as the runtime does.
@file:Suppress("InjectDispatcher")

package me.ahoo.wow.leases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.runtime.cluster.ClusterChannel
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.spi.wait.Origin
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration

data class Deposit(val amount: Int = 1)
data class Deposited(val amount: Int)

class Account {
    var balance = 0L
}

const val SERVICE = "h6-service"
const val PROJECTOR = "h6.projector"

/**
 * The nodes of one service in one JVM for one repetition: each has its own database clients, kernel, state cache,
 * subscriptions, ownership, router and coordinator, as in production; they reach each other through a
 * [LoopbackClusterChannel] and share the database. Every node uses [options].
 */
class SpikeCluster(
    val backend: Backend,
    val context: String,
    val partitions: Int,
    val options: ClusterOptions,
    val timeline: Timeline,
    val handlings: Handlings,
    val leaseMetrics: LeaseMetrics,
) {
    val group = LeaseGroup(SERVICE, context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val channel = CountingChannel(LoopbackClusterChannel(scope), timeline)
    val nodes = ConcurrentHashMap<String, SpikeNode>()

    /** The nodes the load balancer sends commands to. */
    val receivers: List<SpikeNode> get() = nodes.values.filter { it.receiving }

    /** The nodes taking part in the group: started, and neither leaving nor dead. */
    val members: List<SpikeNode> get() = nodes.values.filter { it.member }

    fun newNode(name: String): SpikeNode = SpikeNode(name, this, backend.node(context, partitions)).also {
        check(nodes.putIfAbsent(name, it) == null) { "$name exists." }
    }

    fun partitionOf(id: String): Int = Partitioner.partitionOf(ref(id), partitions)

    fun ref(id: String) = AggregateType(context, "account").ref(TENANT, id)

    fun close() {
        scope.cancel()
    }

    companion object {
        const val TENANT = "t"
    }
}

/** One node: what a production node wires for one context, with counting wrappers around the stores and channel. */
class SpikeNode(val name: String, private val cluster: SpikeCluster, private val stores: NodeStores) {
    val address = "$name.internal:7070"

    /** Whether the load balancer sends commands here. */
    @Volatile
    var receiving = false

    /** Whether the node takes part in the group (it is counted in the expected HRW assignment). */
    @Volatile
    var member = false

    /** Set when the node is killed: from then on it neither saves checkpoints nor records handlings. */
    @Volatile
    var dead = false
        private set

    private val log = CountingLog(stores.log, cluster.timeline)
    private val leases = CountingLeases(stores.leases, cluster.leaseMetrics, cluster.timeline)

    val model = aggregate<Account>(cluster.context, "account") {
        initial { Account() }
        decide<Deposit>(Creation.ALLOWED) { _, body ->
            if (!ownership.accepts(cluster.partitionOf(command.ref.id))) {
                cluster.timeline.now().nonOwner.increment()
            }
            Decision.events(Deposited(body.amount))
        }
        evolve<Deposited> { balance += it.amount }
    }

    private val registry = WaitRegistry()
    private val signals = LocalSignalSender(name, registry) { origin, signal ->
        cluster.nodes[origin.node]?.registry?.signal(signal)
    }
    private val kernel = Kernel(log, states = LruStateCache(CACHE_CAPACITY))
    private val executor = Executor(kernel)
    private val subscriptions = SubscriptionRuntime(
        cluster.group.name,
        log,
        DeadDropsCheckpoints(stores.checkpoints) { dead },
        stores.ledger,
        listOf(
            Subscription(PROJECTOR, SubscriptionFilter(cluster.context)) { entry ->
                if (!dead) {
                    cluster.handlings.handled(entry.ref.id, entry.version, cluster.partitionOf(entry.ref.id))
                }
            },
        ),
        SubscriptionOptions(),
        signals = signals,
    )
    val ownership = PartitionOwnership(
        cluster.group,
        name,
        address,
        cluster.partitions,
        leases,
        cluster.options,
        subscriptions,
    )
    private val router = PartitionedRouter(listOf(ownership), cluster.channel, cluster.options)
    val coordinator = WaitCoordinator(
        executor,
        registry,
        subscriptions,
        signals,
        WaitOptions(Origin(name, address, SERVICE)),
        router = router,
    )
    private val endpoint = ForwardEndpoint(coordinator, listOf(model), listOf(ownership))

    suspend fun start() {
        cluster.channel.delegate.bind(address, endpoint)
        ownership.start()
    }

    /** Leaves gracefully (hand-off of every partition), then stops serving the cluster channel. */
    suspend fun leave() {
        ownership.stop()
        cluster.channel.delegate.unbind(address)
    }

    /**
     * Dies at once, as a crashed process: unreachable, no checkpoint saved from now on, nothing handled any more, and
     * its membership and leases left to expire. [PartitionOwnership]'s own `abort` (internal to `:runtime`, made
     * for such tests) stops its loops without handing off or leaving.
     */
    suspend fun kill() {
        dead = true
        cluster.channel.delegate.unbind(address)
        abort(ownership)
    }

    fun command(id: String, commandId: String): Command<Deposit> =
        Command(commandId, "r-$commandId", cluster.ref(id), Deposit(), wait = WaitPlan.COMMITTED)

    suspend fun close() {
        executor.close()
        stores.close()
    }

    private companion object {
        const val CACHE_CAPACITY = 10_000

        val ABORT: Method = PartitionOwnership::class.java.declaredMethods
            .single { it.name.startsWith("abort$") }
            .also { it.isAccessible = true }

        @Suppress("RedundantSuspendModifier") // It suspends through the intrinsic; detekt does not see that.
        suspend fun abort(ownership: PartitionOwnership) {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation -> ABORT.invoke(ownership, continuation) }
        }
    }
}

/** An [EventLog] that counts the appends that lost the version slot. */
class CountingLog(private val delegate: EventLog, private val timeline: Timeline) : EventLog by delegate {
    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        delegate.append(stream, transport).also {
            if (it is AppendOutcome.Conflict) {
                timeline.now().conflicts.increment()
            }
        }
}

/** A [CheckpointStore] that stops saving once its node is dead, as a crashed process would. */
class DeadDropsCheckpoints(private val delegate: CheckpointStore, private val dead: () -> Boolean) :
    CheckpointStore by delegate {
    override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
        if (!dead()) {
            delegate.save(group, subscription, partition, position)
        }
    }
}

/** A [ClusterChannel] over a [LoopbackClusterChannel] that counts forwards and their failures. */
class CountingChannel(val delegate: LoopbackClusterChannel, private val timeline: Timeline) : ClusterChannel {
    override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
        val second = timeline.now()
        second.forwards.increment()
        try {
            return delegate.forward(address, request).also {
                if (it.refused != null) {
                    second.forwardFailures.increment()
                }
            }
        } catch (cancelled: CancellationException) {
            second.forwardFailures.increment()
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            second.forwardFailures.increment()
            throw failure
        }
    }
}

/** A [LeaseStore] that times every call and counts the renewals that found the lease lost. */
class CountingLeases(
    private val delegate: LeaseStore,
    private val metrics: LeaseMetrics,
    private val timeline: Timeline,
) : LeaseStore {
    private suspend fun <T> timed(operation: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            metrics.failures.increment()
            throw failure
        } finally {
            metrics.record(operation, (System.nanoTime() - started) / NANOS_PER_MICRO)
        }
    }

    override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
        timed("acquire") { delegate.acquire(group, partition, owner, ttl) }

    override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
        timed("renew") { delegate.renew(group, partition, owner, ttl) }.also {
            if (it == null) {
                timeline.now().lostLeases.increment()
            }
        }

    override suspend fun release(group: String, partition: Int, owner: String): Boolean =
        timed("release") { delegate.release(group, partition, owner) }

    override suspend fun owners(group: String): List<Lease> = timed("owners") { delegate.owners(group) }

    override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member =
        timed("heartbeat") { delegate.heartbeat(group, node, address, ttl) }

    override suspend fun members(group: String): List<Member> = timed("members") { delegate.members(group) }

    override suspend fun leave(group: String, node: String) = timed("leave") { delegate.leave(group, node) }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}

/** Command ids unique across the whole run. */
object Ids {
    private val next = AtomicLong()

    fun next(): String = "c-${next.incrementAndGet()}"
}
