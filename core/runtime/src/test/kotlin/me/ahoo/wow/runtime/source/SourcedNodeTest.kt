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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.PublishedEvent
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ContractException
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.health.HealthOptions
import me.ahoo.wow.runtime.health.HealthStatus
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.NodeSample
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SourcedSubscription
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.SubscriptionView
import me.ahoo.wow.runtime.node.accountModel
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.node.subscriptionsView
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.transport.PartitionedSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * A node whose annotated handlers read another context's published stream (decisions D134, D138, D140): the bindings
 * are compiled into sourced subscriptions, the node reads the stream's topic, under its prefix, for them, and a
 * handler of a nullable state gets null from a stream published without it.
 */
class SourcedNodeTest {
    private val topics = InMemoryTopics(2).also { it.create(TOPIC, 2) }
    private val channel = LoopbackClusterChannel()
    private val seen = CopyOnWriteArrayList<StateEvent<OrderTotal?>>()
    private val decided = CopyOnWriteArrayList<String>()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()

    /** The subscriber's own view of the published order state. */
    data class OrderTotal(val total: Int)

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private val board = ProcessorBinding.project<OrderTotal?>(
        "Board",
        "onOrder",
        OrderTotal::class.java,
        aggregates = listOf("sales.order"),
        nullableState = true,
    ) { event, _ -> seen += event }

    private val payout = ProcessorBinding.saga<OrderTotal?>(
        "Payout",
        "onOrder",
        OrderTotal::class.java,
        startFrom = StartFrom.BEGINNING,
        aggregates = listOf("sales.order"),
        nullableState = true,
        derives = listOf(Deposit::class.java),
    ) { event, context -> listOf(CommandRequest.to(ACCOUNT.ref(event.ref.tenantId, event.ref.id), Deposit(1))) }

    private fun subscription(name: String, kind: SubscriptionKind, nullable: Boolean = true) = SubscriptionManifest(
        name = name,
        kind = kind,
        source = SubscriptionSource("sales", listOf("order"), nullableState = nullable),
        function = FunctionManifest(
            if (kind == SubscriptionKind.SAGA) FunctionKind.SAGA else FunctionKind.PROJECT,
            BANK,
            name.substringBefore('.'),
            name.substringAfter('.'),
        ),
    )

    private fun manifest(nullable: Boolean = true): Manifest = accountsManifest(BANK).let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map {
                it.copyWith(
                    subscriptions = listOf(
                        subscription("Board.onOrder", SubscriptionKind.PROJECTION, nullable),
                        subscription("Payout.onOrder", SubscriptionKind.SAGA, nullable),
                    ),
                )
            },
        )
    }

    private fun node(
        topics: SourceTopics? = SourceTopics(this.topics, PREFIX),
        bindings: SubscriptionFactory = SubscriptionBindings(mapOf(BANK to listOf(board, payout))),
        manifest: Manifest = manifest(),
    ): ServiceNode = ServiceNode(
        NODE_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(accountModel(BANK, decided)),
        manifest,
        InMemoryBackends(),
        channel,
        options = fastNodeOptions(),
        subscriptions = bindings,
        contracts = ContractDirectory(manifest, listOf(SALES)),
        topics = topics,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    @Test
    fun `a handler of another context's stream should read its topic and take a null state without it`(): Unit =
        runBlocking {
            val node = node()
            node.topicSubscriptions.keys.assert().containsExactly(TOPIC)
            val subscriptions = node.topicSubscriptions.getValue(TOPIC)
            subscriptions.subscriptions.map { it.name }.assert().containsExactly("Board.onOrder", "Payout.onOrder")
            // A sourced handler reads no shared state of the node's own context.
            node.context(BANK).views.readers.keys.assert().doesNotContain("Board.onOrder")
            node.start()
            subscriptions.awaitStarted()
            topics.publish(orderRecord("o-1", 1, withState = false), topic = TOPIC)
            topics.publish(orderRecord("o-1", 2), topic = TOPIC)
            eventually { seen.size == 2 }
            val (stateless, stateful) = seen
            stateless.state.assert().isNull()
            stateless.ref.assert().isEqualTo(order("o-1"))
            stateless.commandType.assert().isEqualTo("sales.order.Place")
            // DX-1c: no class of the sales api is on this class path, so the event is a PublishedEvent.
            (stateless.events.single() as PublishedEvent).name.assert().isEqualTo("sales.order.Placed")
            stateful.state.assert().isEqualTo(OrderTotal(2))
            stateful.version.assert().isEqualTo(2)
            // The saga derives a command of the node's own context for each record, once.
            eventually { decided.size == 2 }
            node.stop()
            subscriptions.runtime.assert().isNull()
        }

    @Test
    fun `the operator's view should list a topic subscription's partitions only while they are held back (D372 S7)`(): Unit =
        runBlocking {
            val down = ProcessorBinding.project<OrderTotal?>(
                "Board",
                "onOrder",
                OrderTotal::class.java,
                aggregates = listOf("sales.order"),
                nullableState = true,
            ) { _, _ -> throw StorageUnavailableException("The board's store is down.") }
            val node = node(bindings = SubscriptionBindings(mapOf(BANK to listOf(down, payout))))
            val subscriptions = node.topicSubscriptions.getValue(TOPIC)
            node.start()
            subscriptions.awaitStarted()
            suspend fun topic() = node.subscriptionsView(NodeSample.EMPTY, maxParked = 10).subscriptions
                .filter { it.group == subscriptions.group.name }
            topic().map { it.id }.assert().containsExactly(
                SubscriptionId(BANK, "Board.onOrder"),
                SubscriptionId(BANK, "Payout.onOrder"),
            )
            topic().map { it.source }.toSet().assert().containsExactly(SubscriptionView.Source.TOPIC)
            topic().flatMap { it.partitions }.assert().isEmpty()

            topics.publish(orderRecord("o-1", 1), topic = TOPIC)
            eventually { subscriptions.paused().isNotEmpty() }

            val board = topic().single { it.id.name == "Board.onOrder" }.partitions.single()
            board.partition.assert().isEqualTo(subscriptions.paused().single().partition)
            board.runs.assert().isTrue()
            board.paused!!.reason.assert().isNotBlank()
            board.head.assert().isNull()
            topic().single { it.id.name == "Payout.onOrder" }.partitions.assert().isEmpty()
        }

    @Test
    fun `a topic still joining should make the node not ready until it reads it (R3-H)`(): Unit = runBlocking {
        val gated = object : PartitionedSource by topics {
            @Volatile
            var open = false

            override suspend fun partitions(topic: String): List<Int> =
                if (open) topics.partitions(topic) else emptyList()
        }
        val node = node(topics = SourceTopics(gated, PREFIX))
        val health = NodeHealth(node, NodeSampler(node), HealthOptions(storeDownAfter = Duration.ZERO))
        node.start()
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.DOWN)
        val topic = readiness.component(NodeHealth.TOPICS)!!.components.single()
        topic.name.assert().isEqualTo(TOPIC)
        topic.status.assert().isEqualTo(HealthStatus.DOWN)
        topic.details["state"].assert().isEqualTo("JOINING")
        eventually {
            (health.readiness().component(NodeHealth.TOPICS)!!.components.single().details["joinFailures"] as Int) > 0
        }
        health.readiness().component(NodeHealth.TOPICS)!!.components.single().details["lastJoinFailure"].assert()
            .isEqualTo("IllegalStateException")
        // A node that does not wait for its topics is ready, its topic degraded.
        NodeHealth(node, NodeSampler(node), HealthOptions(topicsRequired = false)).readiness().let {
            it.status.assert().isEqualTo(HealthStatus.UP)
            it.component(NodeHealth.TOPICS)!!.status.assert().isEqualTo(HealthStatus.DEGRADED)
        }

        gated.open = true
        node.topicSubscriptions.getValue(TOPIC).awaitStarted()
        eventually { health.readiness().status == HealthStatus.UP }
        // The topic's lease group is among the ownerships, and its runtime among the subscriptions.
        health.sample()
        val all = health.health()
        all.component(NodeHealth.OWNERSHIP)!!.components.map { it.name }.assert()
            .contains("$NODE_SERVICE/topic:$TOPIC")
        all.component(NodeHealth.SUBSCRIPTIONS)!!.components.map { it.name }.assert()
            .contains("$NODE_SERVICE/topic:$TOPIC")
    }

    @Test
    fun `a node on standby should be ready, its topics marked standby until it is activated`(): Unit = runBlocking {
        val node = node()
        val health = NodeHealth(node, NodeSampler(node), HealthOptions(storeDownAfter = Duration.ZERO))
        node.start(standby = true)
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.UP)
        val topic = readiness.component(NodeHealth.TOPICS)!!.components.single()
        topic.status.assert().isEqualTo(HealthStatus.UP)
        topic.details["state"].assert().isEqualTo("STOPPED")
        topic.details["standby"].assert().isEqualTo(true)

        node.activate("operator")
        node.topicSubscriptions.getValue(TOPIC).awaitStarted()
        eventually { health.readiness().status == HealthStatus.UP }
        health.readiness().component(NodeHealth.TOPICS)!!.components.single().details.keys.assert()
            .doesNotContain("standby")
        node.stop()
    }

    @Test
    fun `a sourced handler's subscription carries its own retry budget (P3)`() {
        val retry = HandlerRetry(maxAttempts = 2)
        val bindings = SubscriptionBindings(
            mapOf(
                BANK to listOf(
                    ProcessorBinding.project<OrderTotal?>(
                        "Board",
                        "onOrder",
                        OrderTotal::class.java,
                        aggregates = listOf("sales.order"),
                        nullableState = true,
                        retry = retry,
                    ) { _, _ -> },
                    ProcessorBinding.saga<OrderTotal?>(
                        "Payout",
                        "onOrder",
                        OrderTotal::class.java,
                        aggregates = listOf("sales.order"),
                        nullableState = true,
                        retry = retry,
                    ) { _, _ -> emptyList() },
                ),
            ),
        )
        val node = node(bindings = bindings)
        bindings.sourced(node.context(BANK)).map { it.subscription.retry }.assert().containsExactly(retry, retry)
        // Decision D194: a handler of another context's stream, the saga too, starts from the beginning by default.
        bindings.sourced(node.context(BANK)).map { it.subscription.startFrom }.assert()
            .containsExactly(StartFrom.BEGINNING, StartFrom.JOIN)
    }

    @Test
    fun `a record without its state should fail a handler of a state that is not nullable`(): Unit = runBlocking {
        val strict = ProcessorBinding.project<OrderTotal>(
            "Strict",
            "onOrder",
            OrderTotal::class.java,
            aggregates = listOf("sales.order"),
        ) { _, _ -> }
        val node = node(bindings = SubscriptionBindings(mapOf(BANK to listOf(strict))))
        val sourced = SubscriptionBindings(mapOf(BANK to listOf(strict))).sourced(node.context(BANK)).single()
        sourced.types.assert().containsExactly(ORDER)
        val entry = me.ahoo.wow.runtime.subscription.SubscribedEntry(
            stream = TopicEntries.streamOf(orderRecord("o-1", 1, withState = false)),
            position = null,
            events = emptyList(),
            published = orderRecord("o-1", 1, withState = false),
        )
        assertThrows<IllegalStateException> {
            SubscriptionBindings.publishedStateEventOf(entry, strict, node.context(BANK))
        }.message.assert().contains("not nullable")
        SubscriptionBindings.publishedStateEventOf(
            entry.copy(published = orderRecord("o-1", 1)),
            strict,
            node.context(BANK)
        )
            .state.assert().isEqualTo(OrderTotal(1))
        // StateEvent<*>: the published state as it is, or null.
        val any = ProcessorBinding.project<Any>("Any", "onOrder", null, aggregates = listOf("sales.order")) { _, _ -> }
        val tree = SubscriptionBindings.publishedStateEventOf(
            entry.copy(published = orderRecord("o-1", 1)),
            any,
            node.context(BANK),
        ).state
        tree.toString().assert().isEqualTo("""{"total":1}""")
        SubscriptionBindings.publishedStateEventOf(entry, any, node.context(BANK)).state.assert().isNull()
        // A ledger entry with no offset to re-scan from cannot be retried.
        assertThrows<IllegalStateException> {
            runBlocking { TopicEntries(TOPIC, topics, 2).retryEntries(order("o-1"), 1, null) }
        }.message.assert().contains("no offset")
    }

    @Test
    fun `a node with sourced subscriptions should need a source of topics and a published stream`() {
        val sourceless = assertThrows<WowConfigurationException> { node(topics = null) }
        sourceless.message.assert().contains("reads no topics")
        sourceless.key.assert().isEqualTo("wow.transport")
        val unpublished = object : SubscriptionFactory {
            override fun subscriptions(context: me.ahoo.wow.runtime.node.ContextNode) = emptyList<Subscription>()

            override fun sourced(context: me.ahoo.wow.runtime.node.ContextNode) = listOf(
                SourcedSubscription(
                    Subscription("Ledger.onEntry", SubscriptionFilter("sales")) {},
                    setOf(AggregateType("sales", "ledger")),
                ),
            )
        }
        assertThrows<IllegalArgumentException> { node(bindings = unpublished) }.message.assert()
            .contains("which no Manifest of the service or its peers publishes")
        assertThrows<IllegalArgumentException> {
            SourcedSubscription(Subscription("A.b", SubscriptionFilter("bank")) {}, setOf(ORDER))
        }
        // A sourced subscription belongs to the context that reads: its hops and signals are of that context.
        val foreign = object : SubscriptionFactory {
            override fun subscriptions(context: me.ahoo.wow.runtime.node.ContextNode) = emptyList<Subscription>()

            override fun sourced(context: me.ahoo.wow.runtime.node.ContextNode) =
                listOf(
                    SourcedSubscription(Subscription("Foreign.onOrder", SubscriptionFilter("sales")) {}, setOf(ORDER))
                )
        }
        assertThrows<IllegalArgumentException> { node(bindings = foreign) }.message.assert()
            .contains("belongs to sales")
        Subscription("Own.onOrder", SubscriptionFilter("sales"), context = BANK) {}.function.context
            .assert().isEqualTo(BANK)
        // Decision D140: a handler of a state that is not nullable cannot read a stream published without it.
        assertThrows<ContractException> { node(manifest = manifest(nullable = false)) }
    }

    private companion object {
        const val BANK = "bank"
        const val PREFIX = "test."
        const val TOPIC = PREFIX + ORDER_TOPIC
        val ACCOUNT = AggregateType(BANK, "account")

        /** The sales service publishes its orders without their state. */
        val SALES = ManifestFragment(
            "sales-api.jar!/META-INF/wow/contract.json",
            Manifest(
                contexts = listOf(
                    ContextManifest(
                        "sales",
                        aggregates = listOf(
                            AggregateManifest("order", state = StateManifest(Manifest.schemaRef("sales.Order"))),
                            AggregateManifest("ledger", state = StateManifest(Manifest.schemaRef("sales.Ledger"))),
                        ),
                        published = listOf(PublishedManifest("order", ORDER_TOPIC, includeState = false)),
                    ),
                ),
            ),
        )
    }
}
