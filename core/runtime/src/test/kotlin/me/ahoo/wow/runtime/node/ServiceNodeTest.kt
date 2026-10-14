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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.RecordingLeases
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.ContractFixtures
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.UnknownContexts
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The node assembly (decision D108): admission to commit through one node, several contexts, two nodes, and stop. */
class ServiceNodeTest {
    /** While not null, the replies of node-2 wait for it. */
    @Volatile
    private var replyGate: CompletableDeferred<Unit>? = null

    private val channel = LoopbackClusterChannel(latency = { address ->
        if (address.startsWith("node-2")) {
            replyGate?.await()
        }
    })
    private val nodes = ConcurrentHashMap<String, ServiceNode>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        replyGate?.complete(Unit)
        nodes.values.forEach { it.stop() }
    }

    private fun node(
        name: String,
        vararg contexts: String = arrayOf(BANK),
        backends: BackendFactory = InMemoryBackends(),
        decided: MutableList<String> = CopyOnWriteArrayList(),
        options: ServiceNodeOptions = fastNodeOptions(),
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
        listener: (ServiceNode.Step) -> Unit = {},
        address: String? = "$name.internal:9090",
        peers: List<ManifestFragment> = emptyList(),
    ): ServiceNode = ServiceNode(
        NODE_SERVICE,
        name,
        address,
        contexts.map { accountModel(it, decided) },
        accountsManifest(*contexts),
        backends,
        channel,
        remoteSignals = { origin, signal -> nodes[origin.node]?.waits?.signal(signal) },
        options = options,
        subscriptions = subscriptions,
        listener = listener,
        contracts = ContractDirectory(accountsManifest(*contexts), peers),
    ).also {
        nodes[name] = it
        if (it.hasAddress) {
            channel.bind(it.address, it.forwardHandler)
        }
    }

    private fun deposit(
        context: String = BANK,
        id: String? = null,
        amount: Int = 1,
        wait: WaitPlan? = WaitPlan.COMMITTED,
    ) = CommandRequest(
        context = context,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":$amount}"""),
        caller = Caller(TENANT),
        aggregateId = id,
        wait = wait,
    )

    @Test
    fun `a single node admits, executes and snapshots commands through its gateway`(): Unit = runBlocking {
        val backends = InMemoryBackends()
        val decided = CopyOnWriteArrayList<String>()
        val node = node("node-1", backends = backends, decided = decided)
        node.state.assert().isEqualTo(ServiceNode.State.NEW)
        node.isReady.assert().isFalse()

        node.start()
        node.isReady.assert().isTrue()
        node.context(BANK).ownership.owned.size.assert().isEqualTo(8)

        // The default wait: the snapshot subscription writes the new aggregate, whose id admission generated.
        val created = node.gateway.execute(deposit(amount = 5, wait = null))
        created.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        created.hop.assert().isEqualTo(Hop.snapshot(BANK))
        created.version.assert().isEqualTo(1)
        backends.opened.getValue(BANK).snapshots!!.load(created.ref)!!.state.assert().isEqualTo("""{"balance":5}""")

        val second = node.gateway.execute(deposit(id = created.ref.id, amount = 2))
        second.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        second.version.assert().isEqualTo(2)
        decided.assert().containsExactly(created.commandId, second.commandId)

        node.stop()
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        node.isReady.assert().isFalse()
        val closed = node.gateway.execute(deposit(id = created.ref.id))
        closed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        closed.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        node.stop()
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
    }

    @Test
    fun `each context of a node has its own log, lease group and endpoint`(): Unit = runBlocking {
        val backends = InMemoryBackends()
        val node = node("node-1", BANK, LEDGER, backends = backends)
        node.contexts.keys.assert().containsExactlyInAnyOrder(BANK, LEDGER)
        node.context(LEDGER).group.name.assert().isEqualTo("$NODE_SERVICE/$LEDGER")
        node.start()

        val bank = node.gateway.execute(deposit(BANK, id = "a-1"))
        val ledger = node.gateway.execute(deposit(LEDGER, id = "a-1", amount = 3))
        bank.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        ledger.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        ledger.ref.type.assert().isEqualTo(AggregateType(LEDGER, "account"))
        backends.opened.getValue(BANK).log.findByRequestId(TENANT, ledger.requestId).assert().isNull()
        backends.opened.getValue(LEDGER).log.findByRequestId(TENANT, ledger.requestId)!!.version.assert().isEqualTo(1)
        backends.opened.getValue(BANK).leases.owners("$NODE_SERVICE/$BANK").size.assert().isEqualTo(8)
        backends.opened.getValue(LEDGER).leases.owners("$NODE_SERVICE/$LEDGER").size.assert().isEqualTo(8)

        // A forward reaches the endpoint of its command's context; one of a context not hosted here is refused.
        val model = node.context(LEDGER).models.single()
        val command = Command("c-1", "r-1", model.type.ref(TENANT, "a-2"), Deposit())
        val wire = ClusterCodec.wire(model, command)
        val later = System.currentTimeMillis() + 10_000
        node.forwardHandler.forward(ForwardCommand(command = wire, deadline = later)).result!!.status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val elsewhere = wire.copy(ref = AggregateType("audit", "account").ref(TENANT, "a-2"))
        node.forwardHandler.forward(ForwardCommand(command = elsewhere, deadline = later)).refused
            .assert().isEqualTo(Refusal.UNKNOWN_TYPE)
    }

    @Test
    fun `two nodes forward a command to its owner, which signals the node it arrived at`(): Unit = runBlocking {
        val backends = InMemoryBackends(partitions = 16)
        val options = fastNodeOptions().copy(cluster = fastOptions(forwardTimeout = 10.seconds))
        val decided = mapOf("node-1" to CopyOnWriteArrayList<String>(), "node-2" to CopyOnWriteArrayList())
        val projected = CopyOnWriteArrayList<String>()
        val projector = SubscriptionFactory { context ->
            listOf(Subscription(PROJECTOR, SubscriptionFilter(context.context)) { projected += it.ref.id })
        }
        val (one, two) = decided.map { (name, commands) ->
            node(name, backends = backends, decided = commands, options = options, subscriptions = projector)
        }
        one.start()
        two.start()
        val names = listOf("node-1", "node-2")
        eventually(15.seconds) {
            listOf(one, two).all { node ->
                val ownership = node.context(BANK).ownership
                ownership.owned == Hrw.partitionsOf(node.node, names, 16) &&
                    ownership.routes.owners.mapValues { it.value.node } == Hrw.assignment(names, 16)
            }
        }
        val id = (0 until 64).map { "acc-$it" }.first {
            Hrw.owner(Partitioner.partitionOf(AggregateType(BANK, "account").ref(TENANT, it), 16), names) == "node-2"
        }

        val result = one.gateway.execute(deposit(id = id, wait = WaitPlan.of(Hop.handled(PROJECTOR))))

        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.handled(PROJECTOR, BANK))
        decided.getValue("node-2").assert().containsExactly(result.commandId)
        decided.getValue("node-1").assert().isEmpty()
        projected.assert().contains(id)
        one.waits.size.assert().isEqualTo(0)
    }

    @Test
    fun `a node stops in order - hand off, let the waits end, close the gateway, the executors and the logs`(): Unit =
        runBlocking {
            val events = CopyOnWriteArrayList<String>()
            val inMemory = InMemoryBackends()
            val backends = BackendFactory { context ->
                inMemory.backends(context).let { it.copy(leases = RecordingLeases(it.leases, events)) }
            }
            val entered = CompletableDeferred<Unit>()
            val stuck = SubscriptionFactory { context ->
                listOf(
                    Subscription(PROJECTOR, SubscriptionFilter(context.context)) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    },
                )
            }
            lateinit var node: ServiceNode
            node = node(
                "node-1",
                backends = backends,
                options = fastNodeOptions().copy(subscription = testOptions(stopTimeout = 100.milliseconds)),
                subscriptions = stuck,
                listener = { step -> events += "$step ${node.waits.size} ${node.state}" },
            )
            node.start()
            val timeout = 2.seconds
            val waiting = async(Dispatchers.Default) {
                node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.of(Hop.handled(PROJECTOR)).within(timeout)))
            }
            entered.await()
            node.waits.size.assert().isEqualTo(1)

            val started = System.nanoTime()
            node.stop()
            val elapsed = ((System.nanoTime() - started) / 1_000_000).milliseconds

            val result = waiting.await()
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            result.hop.assert().isEqualTo(Hop.committed(BANK))
            // Stopping waited for the wait, which ended at its timeout, not sooner.
            elapsed.assert().isGreaterThan(timeout - 500.milliseconds)
            val releases = events.filter { it.startsWith("release ") }
            releases.size.assert().isEqualTo(8)
            events.drop(releases.size).assert().containsExactly(
                "HANDED_OFF 1 STOPPING",
                "WAITS_ENDED 0 STOPPING",
                "GATEWAY_CLOSED 0 STOPPING",
                "EXECUTIONS_ENDED 0 STOPPING",
                "EXECUTORS_CLOSED 0 STOPPING",
                "LOGS_CLOSED 0 STOPPING",
            )
            node.state.assert().isEqualTo(ServiceNode.State.STOPPED)

            // The executor refuses, and the append queue takes nothing.
            val bank = node.context(BANK)
            val model = bank.models.single()
            val late = Command("c-late", "r-late", model.type.ref(TENANT, "a-2"), Deposit())
            bank.coordinator.executeHere(model, late, null).committed.error!!.code
                .assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        }

    @Test
    fun `a node stops only once the commands still executing have settled`(): Unit = runBlocking {
        val backends = InMemoryBackends(partitions = 16)
        val options = fastNodeOptions().copy(cluster = fastOptions(forwardTimeout = 10.seconds))
        val decided = CopyOnWriteArrayList<String>()
        val steps = CopyOnWriteArrayList<ServiceNode.Step>()
        val one = node("node-1", backends = backends, options = options, listener = { steps += it })
        val two = node("node-2", backends = backends, decided = decided, options = options)
        one.start()
        two.start()
        val names = listOf("node-1", "node-2")
        eventually(15.seconds) {
            one.context(BANK).ownership.routes.owners.mapValues { it.value.node } == Hrw.assignment(names, 16)
        }
        val id = (0 until 64).map { "acc-$it" }.first {
            Hrw.owner(Partitioner.partitionOf(AggregateType(BANK, "account").ref(TENANT, it), 16), names) == "node-2"
        }
        replyGate = CompletableDeferred()

        // Its caller goes away while node-2's reply is held: the command goes on executing in node-1's coordinator.
        val caller = launch(Dispatchers.Default) {
            one.gateway.execute(deposit(id = id, wait = WaitPlan.COMMITTED.within(10.seconds)))
        }
        eventually { decided.isNotEmpty() }
        caller.cancelAndJoin()
        one.waits.size.assert().isEqualTo(0)
        one.context(BANK).coordinator.executing.assert().isEqualTo(1)

        val stopping = async(Dispatchers.Default) { one.stop() }
        eventually { ServiceNode.Step.GATEWAY_CLOSED in steps }
        delay(200)
        stopping.isCompleted.assert().isFalse()
        steps.assert().doesNotContain(ServiceNode.Step.EXECUTIONS_ENDED)

        replyGate!!.complete(Unit)
        withTimeout(10.seconds) { stopping.await() }
        steps.takeLast(3).assert().containsExactly(
            ServiceNode.Step.EXECUTIONS_ENDED,
            ServiceNode.Step.EXECUTORS_CLOSED,
            ServiceNode.Step.LOGS_CLOSED,
        )
        one.context(BANK).coordinator.executing.assert().isEqualTo(0)
        decided.assert().hasSize(1)
    }

    @Test
    fun `a node built without an address learns it before it starts`(): Unit = runBlocking {
        val backends = InMemoryBackends()
        val node = node("node-1", backends = backends, address = null)
        node.hasAddress.assert().isFalse()
        assertThrows<IllegalStateException> { node.address }
        assertThrows<IllegalStateException> { runBlocking { node.start() } }.message.assert().contains("bind")
        node.state.assert().isEqualTo(ServiceNode.State.NEW)
        assertThrows<IllegalArgumentException> { node.bind(" ") }

        node.bind("127.0.0.1:41234")
        node.address.assert().isEqualTo("127.0.0.1:41234")
        node.context(BANK).ownership.address.assert().isEqualTo("127.0.0.1:41234")
        node.context(BANK).coordinator.options.origin.address.assert().isEqualTo("127.0.0.1:41234")
        assertThrows<IllegalStateException> { node.bind("127.0.0.1:41235") }
        channel.bind(node.address, node.forwardHandler)
        node.start()
        backends.opened.getValue(BANK).leases.members("$NODE_SERVICE/$BANK").single().address
            .assert().isEqualTo("127.0.0.1:41234")
        assertThrows<IllegalStateException> { node.bind("127.0.0.1:41236") }
        node.gateway.execute(deposit(id = "a-1")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a start configured for a subscription the node does not have is refused`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun starts(vararg names: String) = fastNodeOptions().let {
            it.copy(subscription = it.subscription.copy(starts = names.associateWith { SubscriptionStart.Beginning }))
        }
        // The application's subscriptions and the built-in ones may be configured (decision D154).
        node("node-1", options = starts("plain", "snapshot"), subscriptions = plain)
        assertThrows<IllegalArgumentException> {
            node("node-2", options = starts("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("subscription Plain.typo", "plain")
    }

    @Test
    fun `a parked retry spec configured for a subscription the node does not have is refused`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun specs(vararg names: String) = fastNodeOptions().let {
            val parked = ParkedRetryOptions(specs = names.associateWith { ParkedRetrySpec(maxRetries = 1) })
            it.copy(subscription = it.subscription.copy(parked = parked))
        }
        val node = node("node-1", options = specs("plain", "snapshot"), subscriptions = plain)
        node.parked.subscriptions.assert().contains("snapshot")
        assertThrows<IllegalArgumentException> {
            node("node-2", options = specs("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("parked retry spec", "subscription Plain.typo", "plain")
    }

    @Test
    fun `a retry budget configured for a subscription the node does not have is refused (P3)`() {
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(BANK)) {}) }
        fun retries(vararg names: String) = fastNodeOptions().let {
            it.copy(
                subscription = it.subscription.copy(retries = names.associateWith { HandlerRetry(maxAttempts = 1) })
            )
        }
        node("node-1", options = retries("plain", "snapshot"), subscriptions = plain)
        assertThrows<IllegalArgumentException> {
            node("node-2", options = retries("Plain.typo"), subscriptions = plain)
        }.message.assert().contains("retry budget", "subscription Plain.typo", "plain")
    }

    @Test
    fun `the node's default wait timeout is its gateway's`() {
        val options = fastNodeOptions().copy(defaultWaitTimeout = 3.seconds)
        node("node-1", options = options).gateway.defaultWaitTimeout(BANK).assert().isEqualTo(3.seconds)
        assertThrows<IllegalArgumentException> { fastNodeOptions().copy(defaultWaitTimeout = Duration.ZERO) }
    }

    @Test
    fun `a node whose Manifest disagrees with its models is not built`() {
        val manifest = accountsManifest(BANK, LEDGER)
        assertThrows<me.ahoo.wow.runtime.manifest.ManifestMismatchException> {
            ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK), accountModel(AUDIT)),
                manifest,
                InMemoryBackends(),
                channel,
            )
        }.differences.assert().containsExactlyInAnyOrder(
            "aggregate $AUDIT.account has a model but is not in the Manifest",
            "aggregate $LEDGER.account is in the Manifest but has no model",
        )
    }

    @Test
    fun `a wait chain may name a subscription of a peer's context through the node's contracts`(): Unit = runBlocking {
        val peer = WaitPlan.of(Hop.handled("StockProjector.onStock", "inventory")).within(200.milliseconds)
        // A node that knows no Manifest of the context lets the hop through (decision D190): it times out unheard.
        val alone = node("node-1")
        alone.start()
        val unheard = alone.gateway.execute(deposit(id = "a-0", wait = peer))
        unheard.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        unheard.hop.assert().isEqualTo(Hop.committed(BANK))
        alone.stop()
        // Unless it rejects the contexts it does not know.
        val strict = node("node-3", options = fastNodeOptions().copy(unknownWaitContexts = UnknownContexts.REJECT))
        strict.start()
        val refused = strict.gateway.execute(deposit(id = "a-1", wait = peer))
        refused.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        strict.stop()

        val node = node("node-2", backends = InMemoryBackends(), peers = ContractFixtures.peers)
        node.contracts.serviceOf("inventory").assert().isEqualTo("inventory-service")
        node.start()
        // Admitted and committed; no signal comes from the peer here, so the wait ends at COMMITTED.
        val result = node.gateway.execute(deposit(id = "a-2", wait = peer))
        result.error!!.code.assert().isNotEqualTo(ResultCodes.WAIT_INVALID)
        result.hop.assert().isEqualTo(Hop.committed(BANK))
    }

    @Test
    fun `a node refuses contracts of another Manifest`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK)),
                accountsManifest(BANK),
                InMemoryBackends(),
                channel,
                contracts = ContractDirectory(accountsManifest(BANK, LEDGER)),
            )
        }.message.assert().contains("contracts")
    }

    @Test
    fun `a node refuses a Manifest of another service`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                "other-service",
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK)),
                accountsManifest(BANK),
                InMemoryBackends(),
                channel,
            )
        }.message.assert().contains("other-service")
    }

    @Test
    fun `a start that fails stops what it started`(): Unit = runBlocking {
        val inMemory = InMemoryBackends()
        val down = object : LeaseStore by InMemoryLeaseStore() {
            override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member =
                error("The lease store is down.")
        }
        val node = node(
            "node-1",
            BANK,
            LEDGER,
            backends = { context ->
                inMemory.backends(context).let { if (context == LEDGER) it.copy(leases = down) else it }
            },
        )
        assertThrows<IllegalStateException> { runBlocking { node.start() } }
            .message.assert().isEqualTo("The lease store is down.")
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        node.contexts.values.forEach { it.ownership.isRunning.assert().isFalse() }
        assertThrows<IllegalStateException> { runBlocking { node.start() } }
    }

    private companion object {
        const val BANK = "bank"
        const val LEDGER = "ledger"
        const val AUDIT = "audit"
        const val TENANT = "t"
        const val PROJECTOR = "projector"
    }
}
