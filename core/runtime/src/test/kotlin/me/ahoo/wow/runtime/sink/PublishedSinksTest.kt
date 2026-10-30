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

package me.ahoo.wow.runtime.sink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ORDER
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import me.ahoo.wow.runtime.binding.shopManifest
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.subscription.ImportCutover
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * The transport sink over an in-memory transport (design 04 §4, decisions D45, D58, D135): one subscription per published
 * stream, a state reader of its type, writing to the stream's topic, its checkpoint moved only once the broker
 * acknowledged.
 */
class PublishedSinksTest {
    private val backends = InMemoryBackends(partitions = 2)
    private val topics = InMemoryTopics(2)
    private val nodes = ArrayList<ServiceNode>()

    /** The shop publishing its orders with their state and its carts without, on a topic of their own. */
    private val manifest: Manifest = shopManifest().let { shop ->
        shop.copyWith(
            contexts = shop.contexts.map {
                it.copyWith(
                    published = listOf(
                        PublishedManifest("order", PublishedManifest.defaultTopic(SHOP, "order")),
                        PublishedManifest("cart", "custom.cart", includeState = false),
                    ),
                )
            },
        )
    }

    private fun node(
        producer: TopicProducer = topics,
        subscriptions: SubscriptionFactory = PublishedSinks.topics(manifest, producer, topicPrefix = "dev.").factory,
    ): ServiceNode =
        ServiceNode(
            SHOP_SERVICE,
            "node-${nodes.size + 1}",
            "node.internal:9090",
            listOf(orderModel(), cartModel()),
            manifest,
            backends,
            LoopbackClusterChannel(),
            options = fastNodeOptions(),
            subscriptions = subscriptions,
        ).also { nodes += it }

    /**
     * The history migrate-data would have imported: [ids]' orders placed by a node that publishes nothing, then the
     * cut-over recorded at the log's heads as the import records it.
     */
    private suspend fun imported(vararg ids: String) {
        val history = node(subscriptions = SubscriptionFactory { emptyList() })
        history.start()
        ids.forEach { history.place(it) }
        history.stop()
        val stores = backends.opened.getValue(SHOP)
        (0 until stores.log.partitions).forEach { partition ->
            stores.log.head(partition)?.let {
                stores.checkpoints.save(ImportCutover.groupOf(SHOP), ImportCutover.SUBSCRIPTION, partition, it)
            }
        }
    }

    @AfterEach
    fun stop(): Unit = runBlocking { nodes.forEach { it.stop() } }

    private fun request(aggregate: String, command: String, json: String, id: String) = ServerCommandRequest(
        context = SHOP,
        aggregate = aggregate,
        command = command,
        body = CommandBody.of(json),
        caller = Caller(TENANT, Scope(ownerId = "alice")),
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    private suspend fun ServiceNode.place(id: String) =
        gateway.execute(request("order", "Place", """{"items":["apple"]}""", id))

    private suspend fun ServiceNode.fill(id: String) =
        gateway.execute(request("cart", "AddItems", """{"items":["pear"]}""", id))

    private fun records(topic: String): List<StateEventRecord> =
        topics.messages(topic).map { EnvelopeCodec.stateEventOf(it.value) }

    private suspend fun checkpoint(subscription: String, partition: Int): Long? =
        backends.opened.getValue(SHOP).checkpoints.load(LeaseGroup(SHOP_SERVICE, SHOP).name, subscription, partition)

    @Test
    fun `each published stream should reach its topic, with its state as the stream says`(): Unit = runBlocking {
        val node = node()
        node.start()
        node.place("o-1")
        node.fill("alice")
        eventually { topics.messages("dev.wow.shop.order.state").size == 1 && topics.messages("dev.custom.cart").size == 1 }
        val message = topics.messages("dev.wow.shop.order.state").single()
        message.key.assert().isEqualTo("shop.order/t/o-1")
        message.headers.assert().isEqualTo(
            mapOf("wow-format" to "1", "wow-kind" to "state-event", "wow-type" to "shop.order"),
        )
        val order = records("dev.wow.shop.order.state").single()
        order.events.single().name.assert().isEqualTo("shop.order.Placed")
        order.state!!.state.assert().isEqualTo(WowJson.mapper.readTree("""{"items":["apple"]}"""))
        order.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        val cart = records("dev.custom.cart").single()
        cart.ref.assert().isEqualTo(CART.ref(TENANT, "alice"))
        cart.state.assert().isNull()
    }

    @Test
    fun `a migrated log should publish its history up to the cut-over marked imported, and V10's commits unmarked`(): Unit =
        runBlocking {
            imported("o-1", "o-2")
            val node = node()
            node.start()
            node.place("o-3")
            eventually { topics.messages("dev.wow.shop.order.state").size == 3 }
            records("dev.wow.shop.order.state").associate { it.ref.id to it.imported }.assert()
                .isEqualTo(mapOf("o-1" to true, "o-2" to true, "o-3" to false))
        }

    @Test
    fun `a log never migrated should publish every commit unmarked`(): Unit = runBlocking {
        val node = node()
        node.start()
        node.place("o-1")
        eventually { topics.messages("dev.wow.shop.order.state").size == 1 }
        records("dev.wow.shop.order.state").single().imported.assert().isFalse()
    }

    @Test
    fun `a sink of data should take the imported history as every commit, marked`(): Unit = runBlocking {
        imported("o-1")
        val written = CopyOnWriteArrayList<StateEventRecord>()
        val data = PublishedSinks(PublishedSinks.publishedOf(manifest), "analytics-sink") { _, _ ->
            Sink { written += it }
        }
        val node = node(subscriptions = data.factory)
        node.start()
        node.place("o-2")
        eventually { written.size == 2 }
        written.associate { it.ref.id to it.imported }.assert().isEqualTo(mapOf("o-1" to true, "o-2" to false))
    }

    @Test
    fun `each sink should be a state reader of the type it publishes`(): Unit = runBlocking {
        val sinks = PublishedSinks.topics(manifest, topics)
        val models = listOf(orderModel(), cartModel())
        sinks.factory.stateReaders(SHOP, models).assert()
            .isEqualTo(mapOf("wow.transport-sink.order" to setOf(ORDER), "wow.transport-sink.cart" to setOf(CART)))
        sinks.factory.viewTypes(SHOP, models).assert().containsExactly(ORDER, CART)
        sinks.factory.stateReaders("other", models).assert().isEmpty()
        sinks.toString().assert().isEqualTo("PublishedSinks(wow.transport-sink)")
        val node = node()
        node.context(SHOP).views.readers.keys.assert().contains("wow.transport-sink.order", "wow.transport-sink.cart")
    }

    @Test
    fun `the checkpoint should pass an entry only once the broker acknowledged it`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val waiting = AtomicInteger()
        val gated = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                waiting.incrementAndGet()
                gate.await()
                topics.send(topic, messages)
            }
        }
        val node = node(gated)
        node.start()
        node.place("o-1")
        val position = backends.opened.getValue(SHOP).log.positionOf(ORDER.ref(TENANT, "o-1"), 1)!!
        eventually { waiting.get() >= 1 }
        // Well past the checkpoint interval: still not acknowledged, so not passed.
        delay(100.milliseconds)
        (checkpoint("wow.transport-sink.order", position.partition) ?: 0L).assert().isLessThan(position.sequence)
        gate.complete(Unit)
        eventually { (checkpoint("wow.transport-sink.order", position.partition) ?: 0L) >= position.sequence }
        records("dev.wow.shop.order.state").single().version.assert().isEqualTo(1)
    }

    @Test
    fun `a write the broker refused should be retried until it is acknowledged`(): Unit = runBlocking {
        val refusals = AtomicInteger(2)
        val flaky = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                check(refusals.getAndDecrement() <= 0) { "The broker did not acknowledge." }
                topics.send(topic, messages)
            }
        }
        val node = node(flaky)
        node.start()
        node.place("o-1")
        eventually { topics.messages("dev.wow.shop.order.state").isNotEmpty() }
        records("dev.wow.shop.order.state").map { it.id }.assert().containsExactly("shop.order/t/o-1@1")
        backends.opened.getValue(
            SHOP
        ).ledger.isParked("wow.transport-sink.order", ORDER.ref(TENANT, "o-1")).assert().isFalse()
    }

    @Test
    fun `a node restarted on the same log should publish again only what it had not passed`(): Unit = runBlocking {
        val first = node()
        first.start()
        first.place("o-1")
        eventually { topics.messages("dev.wow.shop.order.state").size == 1 }
        val position = backends.opened.getValue(SHOP).log.positionOf(ORDER.ref(TENANT, "o-1"), 1)!!
        eventually { (checkpoint("wow.transport-sink.order", position.partition) ?: 0L) >= position.sequence }
        first.stop()
        val second = node()
        second.start()
        second.place("o-2")
        eventually { records("dev.wow.shop.order.state").any { it.ref.id == "o-2" } }
        records("dev.wow.shop.order.state").map { it.id }.assert()
            .containsExactlyInAnyOrder("shop.order/t/o-1@1", "shop.order/t/o-2@1")
    }

    @Test
    fun `a context publishing an aggregate it has no model of should not assemble`() {
        val wrong = manifest.copyWith(
            contexts = manifest.contexts.map { it.copyWith(published = listOf(PublishedManifest("missing", "t"))) },
        )
        val sinks =
            PublishedSinks(
                PublishedSinks.publishedOf(wrong),
                PublishedSinks.TRANSPORT_SINK
            ) { _, _ -> TopicSink(topics, "t") }
        assertThrows<IllegalArgumentException> { sinks.factory.stateReaders(SHOP, listOf(orderModel())) }
        assertThrows<IllegalArgumentException> { PublishedSinks(emptyMap(), " ") { _, _ -> TopicSink(topics, "t") } }
        PublishedSinks.publishedOf(shopManifest()).assert().isEmpty()
    }

    @Test
    fun `a topic sink should write nothing for no records`(): Unit = runBlocking {
        val sink = TopicSink(topics, "t")
        sink.write(emptyList())
        topics.messages("t").assert().isEmpty()
        sink.toString().assert().isEqualTo("TopicSink(t)")
        assertThrows<IllegalArgumentException> { TopicSink(topics, " ") }
    }
}
