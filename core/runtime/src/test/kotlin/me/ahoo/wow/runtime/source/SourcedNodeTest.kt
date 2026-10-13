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
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.contract.ContractException
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SourcedSubscription
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.accountModel
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

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
    ) { event, context -> listOf(context.command(ACCOUNT.ref(event.ref.tenantId, event.ref.id), Deposit(1))) }

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
        manifest.copy(
            contexts = manifest.contexts.map {
                it.copy(
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
            // A sourced handler reads no state view of the node's own context.
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
            (stateless.events.single() as StateEventPayload).name.assert().isEqualTo("sales.order.Placed")
            stateful.state.assert().isEqualTo(OrderTotal(2))
            stateful.version.assert().isEqualTo(2)
            // The saga derives a command of the node's own context for each record, once.
            eventually { decided.size == 2 }
            node.stop()
            subscriptions.runtime.assert().isNull()
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
        // A ledger entry with no offset to re-scan from cannot be resumed.
        assertThrows<IllegalStateException> {
            runBlocking { TopicEntries(TOPIC, topics, 2).resume(order("o-1"), 1, null) }
        }.message.assert().contains("no offset")
    }

    @Test
    fun `a node with sourced subscriptions should need a source of topics and a published stream`() {
        assertThrows<IllegalArgumentException> { node(topics = null) }.message.assert().contains("reads no topics")
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
            "META-INF/wow/peers/sales-service.json",
            Manifest(
                service = ServiceInfo("sales-service"),
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
