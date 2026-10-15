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

package me.ahoo.wow.http.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.PartitionedRouter
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two nodes of one service in one JVM, each serving its internal port on bare Reactor Netty and reaching the other's
 * through [HttpClusterChannel] and [HttpSignalSender] over h2c (roadmap 4.2b): they share an in-memory log and lease
 * store, as nodes share their backend.
 */
class HttpClusterTest {
    private val clusters = CopyOnWriteArrayList<HttpCluster>()

    private fun cluster(forwardTimeout: Duration = 10.seconds) = HttpCluster(forwardTimeout).also { clusters += it }

    @AfterEach
    fun close(): Unit = runBlocking {
        clusters.forEach { it.close() }
    }

    @Test
    fun `a command runs on its partition's owner and its later hop is signalled back over HTTP`(): Unit =
        runBlocking {
            val cluster = cluster()
            val (one, two, id) = cluster.forwarded()
            val result = one.deposit(id, WaitPlan.of(Hop.handled(PROJECTOR)))

            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.handled(PROJECTOR, CONTEXT))
            result.function.kind.assert().isEqualTo(FunctionKind.PROJECT)
            two.decided.assert().containsExactly(result.commandId)
            one.decided.assert().isEmpty()
            two.projected.assert().containsExactly(id)
            one.registry.size.assert().isEqualTo(0)
            two.sender.dropped.assert().isEqualTo(0)
            two.server.versions.assert().isNotEmpty().containsOnly("HTTP/2.0")
            one.server.versions.assert().isNotEmpty().containsOnly("HTTP/2.0")
        }

    @Test
    fun `an owner that does not reply in time leaves the outcome unknown, not run here`(): Unit = runBlocking {
        // Decision D223: the owner received the forward, and decides it alone.
        val cluster = cluster(forwardTimeout = 300.milliseconds)
        val (one, two, id) = cluster.forwarded()
        val hold = CompletableDeferred<Unit>()
        two.hold = hold

        val result = one.deposit(id)
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
        one.decided.assert().isEmpty()

        hold.complete(Unit)
        eventually { two.decided.contains(result.commandId) }
        two.hold = null
        eventually { one.deposit(id, commandId = result.commandId).duplicate }
        one.decided.assert().isEmpty()
        two.decided.assert().containsExactly(result.commandId)
    }

    @Test
    fun `an owner whose internal port is down degrades the command to running here`(): Unit = runBlocking {
        val cluster = cluster()
        val (one, two, id) = cluster.forwarded()
        two.server.close()

        val result = one.deposit(id)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        one.decided.assert().containsExactly(result.commandId)
        two.decided.assert().isEmpty()
    }
}

data class Deposit(val amount: Int = 1)
data class Deposited(val amount: Int)

class Account {
    var balance = 0
}

const val SERVICE = "bank-service"
const val CONTEXT = TEST_CONTEXT
const val PROJECTOR = "bank.projector"

/** Nodes of one service sharing an in-memory backend; each has its own internal port and HTTP client. */
class HttpCluster(forwardTimeout: Duration) : AutoCloseable {
    val partitions = 8
    val options = ClusterOptions(
        leaseTtl = 1_500.milliseconds,
        renewInterval = 300.milliseconds,
        memberTtl = 1_500.milliseconds,
        heartbeatInterval = 300.milliseconds,
        reconcileInterval = 100.milliseconds,
        routeRefreshInterval = 100.milliseconds,
        forwardTimeout = forwardTimeout,
        drainTimeout = 2.seconds,
    )
    val log = InMemoryEventLog(partitions)
    val leases = InMemoryLeaseStore()
    val checkpoints = InMemoryCheckpointStore()
    val ledger = InMemoryFailureLedger()
    val group = LeaseGroup(SERVICE, CONTEXT)
    private val nodes = CopyOnWriteArrayList<HttpNode>()
    private val sequence = AtomicInteger()

    fun nextId(): String = "c-${sequence.incrementAndGet()}"

    /** Two running nodes with stable ownership, and an account the second one owns. */
    suspend fun forwarded(): Triple<HttpNode, HttpNode, String> {
        val one = HttpNode("node-1", this).also { nodes += it }
        val two = HttpNode("node-2", this).also { nodes += it }
        one.ownership.start()
        two.ownership.start()
        val names = nodes.map { it.name }
        val expected = Hrw.assignment(names, partitions)
        eventually(15.seconds) {
            nodes.all { node ->
                node.ownership.owned == Hrw.partitionsOf(node.name, names, partitions) &&
                    node.ownership.routes.owners.mapValues { it.value.node } == expected
            }
        }
        val id = (0 until 64).map { "acc-$it" }
            .first { Hrw.owner(Partitioner.partitionOf(ref(it), partitions), names) == two.name }
        return Triple(one, two, id)
    }

    override fun close() = runBlocking {
        nodes.forEach { it.close() }
    }
}

/** One node: kernel, projector, ownership, router and coordinator, its internal port served over real HTTP. */
class HttpNode(val name: String, private val cluster: HttpCluster) {
    val decided = CopyOnWriteArrayList<String>()
    val projected = CopyOnWriteArrayList<String>()

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

    val registry = WaitRegistry()
    private val client = InternalHttp.client()
    val sender = HttpSignalSender(client)
    private val signals = LocalSignalSender(name, registry, sender)
    private val endpoint: ClusterChannelHandler by lazy {
        ForwardEndpoint(coordinator, listOf(model), listOf(ownership))
    }
    private val handler = ClusterChannelHandler { endpoint.forward(it) }
    val server = InternalServer(internalRoutes(handler, registry))
    private val kernel = Kernel(cluster.log)
    private val subscriptions = SubscriptionRuntime(
        cluster.group.name,
        cluster.log,
        cluster.checkpoints,
        cluster.ledger,
        listOf(Subscription(PROJECTOR, SubscriptionFilter(CONTEXT)) { projected += it.ref.id }),
        signals = signals,
    )
    val ownership = PartitionOwnership(
        cluster.group,
        name,
        server.address,
        cluster.partitions,
        cluster.leases,
        cluster.options,
        subscriptions,
    )
    private val coordinator = WaitCoordinator(
        Executor(kernel),
        registry,
        subscriptions,
        signals,
        WaitOptions(Origin(name, server.address, SERVICE)),
        router = PartitionedRouter(listOf(ownership), HttpClusterChannel(client), cluster.options),
    )

    suspend fun deposit(
        id: String,
        wait: WaitPlan = WaitPlan.COMMITTED,
        commandId: String = cluster.nextId(),
    ): CommandResult = coordinator.execute(model, Command(commandId, "r-$commandId", ref(id), Deposit(), wait = wait))

    suspend fun close() {
        hold?.complete(Unit)
        ownership.stop()
        sender.close()
        server.close()
    }
}
