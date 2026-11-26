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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
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
import me.ahoo.wow.runtime.source.orderRecord
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.subscription.ImportCutover
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport sink over an in-memory transport (design 04 §4, decisions D45, D58, D135): one subscription per published
 * stream, a state reader of its type, writing to the stream's topic, its checkpoint moved only once the broker
 * acknowledged.
 */
class PublishedSinksTest {
    private val backends = InMemoryBackends(partitions = PARTITIONS)
    private val topics = InMemoryTopics(PARTITIONS)
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
        subscriptions: SubscriptionFactory =
            PublishedSinks.topics(manifest, producer, UNMASKED, PARTITIONS, topicPrefix = "dev.").factory,
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
            mapOf(
                "wow-format" to "1",
                "wow-kind" to "state-event",
                "wow-type" to "shop.order",
                "wow-partitions" to "$PARTITIONS",
            ),
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
    fun `the transport sink should publish states and event bodies masked as a read that reveals nothing`(): Unit =
        runBlocking {
            val masks = FieldMasks("items", setOf("shop.order.Placed"))
            val sinks = PublishedSinks.topics(
                manifest,
                topics,
                PublicationMasks(masks, true),
                PARTITIONS,
                topicPrefix = "dev.",
            )
            val node = node(subscriptions = sinks.factory)
            node.start()
            node.place("o-1")
            eventually { topics.messages("dev.wow.shop.order.state").size == 1 }
            val order = records("dev.wow.shop.order.state").single()
            order.state!!.state.assert().isEqualTo(WowJson.mapper.readTree("""{"items":"***"}"""))
            order.events.single().payload["items"].asString().assert().isEqualTo("***")
            // Every sensitive value, DISPLAY ones too: no stream is declared revealing.
            masks.asked.assert().contains(
                "state shop.order revealsDisplay=false",
                "event shop.order shop.order.Placed revealsDisplay=false",
            )
            masks.asked.assert().allMatch { it.endsWith("revealsDisplay=false") }
        }

    @Test
    fun `a stream declared revealing should keep its DISPLAY values raw only while the service shares them`(): Unit =
        runBlocking {
            val revealing = manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        published = context.published.map { it.copyWith(revealsDisplay = it.aggregate == "order") },
                    )
                },
            )
            mapOf(true to "true", false to "false").forEach { (shared, orderReveals) ->
                val masks = FieldMasks("items", emptySet())
                val sinks = PublishedSinks.topics(revealing, topics, PublicationMasks(masks, shared), PARTITIONS)
                val node = node(subscriptions = sinks.factory)
                node.start()
                // The cart's stream is not declared revealing; the order's is, and the switch off masks it all the same.
                masks.asked.assert().contains(
                    "state shop.order revealsDisplay=$orderReveals",
                    "state shop.cart revealsDisplay=false",
                )
                node.stop()
            }
        }

    @Test
    fun `a stream reveals its DISPLAY values when declared so and shared, the switch only tightening`() {
        val stream = PublishedManifest("order", "t")
        PublicationMasks(NoMasks, true).revealsDisplay(stream).assert().isFalse()
        PublicationMasks(NoMasks, true).revealsDisplay(stream.copyWith(revealsDisplay = true)).assert().isTrue()
        PublicationMasks(NoMasks, false).revealsDisplay(stream.copyWith(revealsDisplay = true)).assert().isFalse()
        PublicationMasks(NoMasks, false).toString().assert().contains("displayShared=false")
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
        val data = PublishedSinks(PublishedSinks.publishedOf(manifest), "analytics-sink", masks = null) { _, _ ->
            Sink { written += it }
        }
        val node = node(subscriptions = data.factory)
        node.start()
        node.place("o-2")
        eventually { written.size == 2 }
        written.associate { it.ref.id to it.imported }.assert().isEqualTo(mapOf("o-1" to true, "o-2" to false))
    }

    /**
     * D485 ④: a record the transport sink publishes, which other contexts read, leaves out the client's address and
     * `User-Agent`, the facts of where the request came from; the application goes on. A store's sink, the service's
     * own, keeps them, as the log does.
     */
    @Test
    fun `the transport sink should publish no client address or User-Agent, which a store's sink keeps`(): Unit =
        runBlocking {
            val facts = mapOf(RequestFacts.REMOTE_IP to "203.0.113.7", RequestFacts.USER_AGENT to "curl/8.9")
            val node = node().also { it.start() }
            val console = Caller(TENANT, Scope(ownerId = "alice"), attributes = mapOf(Caller.APP to listOf("console")))
            node.gateway.execute(
                request("order", "Place", """{"items":["apple"]}""", "o-1").copyWith(caller = console, headers = facts),
            ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val committed = backends.opened.getValue(SHOP).log.read(ORDER.ref(TENANT, "o-1")).toList().single()
            committed.headers.assert().containsAllEntriesOf(facts).containsEntry(RequestFacts.APP_ID, "console")
            eventually { records("dev.wow.shop.order.state").size == 1 }
            records("dev.wow.shop.order.state").single().headers.assert()
                .isEqualTo(committed.headers - RequestFacts.REMOTE_IP - RequestFacts.USER_AGENT)
                .containsEntry(RequestFacts.APP_ID, "console")
            node.stop()
            val written = CopyOnWriteArrayList<StateEventRecord>()
            val data = PublishedSinks(PublishedSinks.publishedOf(manifest), "analytics-sink", masks = null) { _, _ ->
                Sink { written += it }
            }
            node(subscriptions = data.factory).start()
            eventually { written.isNotEmpty() }
            written.single().headers.assert().isEqualTo(committed.headers)
        }

    @Test
    fun `the transport sink alone should publish a commit of only retired events, as a record that holds none`(): Unit =
        runBlocking {
            val history = node(subscriptions = SubscriptionFactory { emptyList() })
            history.start()
            history.place("o-1")
            history.stop()
            // Version 2 holds only an event every model retired: a commit, though no model reads it.
            val log = backends.opened.getValue(SHOP).log
            val first = log.read(ORDER.ref(TENANT, "o-1")).toList().single()
            val retired = EventRecord("wow.lifecycle.TagsApplied", 1, """{"tags":{}}""")
            val stream = first.copyWith(
                version = 2,
                commandId = "c-retired",
                requestId = "r-retired",
                events = listOf(retired),
            )
            log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            val written = CopyOnWriteArrayList<StateEventRecord>()
            val data = PublishedSinks(PublishedSinks.publishedOf(manifest), "analytics-sink", masks = null) { _, _ ->
                Sink { written += it }
            }
            // A store's sink writes nothing for it, and moves past it.
            val second = log.positionOf(ORDER.ref(TENANT, "o-1"), 2)!!
            val store = node(subscriptions = data.factory).also { it.start() }
            eventually { (checkpoint("analytics-sink.order", second.partition) ?: 0L) >= second.sequence }
            written.map { it.version }.assert().containsExactly(1)
            store.stop()
            // The transport sink publishes it: the aggregate's versions on its topic are contiguous.
            node().start()
            eventually { records("dev.wow.shop.order.state").size == 2 }
            records("dev.wow.shop.order.state").map { it.version to it.events.size }.assert()
                .containsExactly(1 to 1, 2 to 0)
        }

    @Test
    fun `each sink should be a state reader of the type it publishes`(): Unit = runBlocking {
        val sinks = PublishedSinks.topics(manifest, topics, UNMASKED, PARTITIONS)
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
        // The write's send is held: not acknowledged, so the entry is not complete and its checkpoint not passed.
        eventually { waiting.get() >= 1 }
        (checkpoint("wow.transport-sink.order", position.partition) ?: 0L).assert().isLessThan(position.sequence)
        gate.complete(Unit)
        eventually { (checkpoint("wow.transport-sink.order", position.partition) ?: 0L) >= position.sequence }
        records("dev.wow.shop.order.state").single().version.assert().isEqualTo(1)
    }

    @Test
    fun `a batching sink should be handed an aggregate's commits through the worker in version order`(): Unit =
        runBlocking {
            // How runs waiting together are batched is SinkWritesTest's and SinkSubscriptionTest's, with gates of their
            // own; through the worker, the order and the write held until it returns.
            val gate = CompletableDeferred<Unit>()
            val writes = CopyOnWriteArrayList<List<String>>()
            val batching = object : Sink {
                override val maxBatch: Int = 8

                override suspend fun write(records: List<StateEventRecord>) {
                    writes += records.map { it.id }
                    gate.await()
                }
            }
            val published = manifest.contexts.associate { it.name to it.published }
            val sinks = PublishedSinks(published, "wow.test-sink", masks = null) { _, _ -> batching }
            val node = node(subscriptions = sinks.factory)
            node.start()
            node.fill("c-1")
            eventually { writes.size == 1 }
            repeat(4) { node.fill("c-1") }
            gate.complete(Unit)
            eventually { writes.sumOf { it.size } == 5 }
            writes.first().assert().containsExactly("shop.cart/t/c-1@1")
            writes.flatten().assert().containsExactlyElementsOf((1..5).map { "shop.cart/t/c-1@$it" })
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
        ).ledger.isParked(SubscriptionId(SHOP, "wow.transport-sink.order"), ORDER.ref(TENANT, "o-1")).assert().isFalse()
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
                PublishedSinks.TRANSPORT_SINK,
                UNMASKED,
            ) { _, _ -> TopicSink(topics, "t", PARTITIONS) }
        assertThrows<IllegalArgumentException> { sinks.factory.stateReaders(SHOP, listOf(orderModel())) }
        assertThrows<IllegalArgumentException> {
            PublishedSinks(emptyMap(), " ", UNMASKED) { _, _ -> TopicSink(topics, "t", PARTITIONS) }
        }
        PublishedSinks.publishedOf(shopManifest()).assert().isEmpty()
    }

    @Test
    fun `a topic sink should write nothing for no records`(): Unit = runBlocking {
        val sink = TopicSink(topics, "t", PARTITIONS)
        sink.write(emptyList())
        topics.messages("t").assert().isEmpty()
        sink.toString().assert().isEqualTo("TopicSink(t)")
        assertThrows<IllegalArgumentException> { TopicSink(topics, " ", PARTITIONS) }
    }

    @Test
    fun `a record goes to its aggregate's partition in the log, whatever partitions the topic has`(): Unit = runBlocking {
        // #6783: the topic grew to eight; the key's hash would pick among eight, the log's partitioner among two.
        val grown = InMemoryTopics(PARTITIONS).also { it.create("grown", 8) }
        val sink = TopicSink(grown, "grown", PARTITIONS)
        val records = (1..32).map { orderRecord("o-$it", 1) }
        sink.write(records)
        val placed = (0 until 8).flatMap { partition ->
            val end = grown.offsets("grown", partition).end
            grown.read("grown", partition, 0, end).toList().map { it.key to partition }
        }
        placed.toSet().assert()
            .isEqualTo(records.map { it.ref.toString() to Partitioner.partitionOf(it.ref, PARTITIONS) }.toSet())
        // Each says so on the wire: the log's count, in the `wow-partitions` header.
        val partition = Partitioner.partitionOf(records.first().ref, PARTITIONS)
        grown.read("grown", partition, 0, 1).toList().single().headers.assert()
            .containsEntry("wow-partitions", "$PARTITIONS").containsEntry("wow-kind", "state-event")
        assertThrows<IllegalArgumentException> { TopicSink(grown, "grown", 3) }
    }

    private companion object {
        /** The log's partitions, which the topics here have too. */
        const val PARTITIONS = 2
    }
}
