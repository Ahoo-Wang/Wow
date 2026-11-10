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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A message whose send the transport did not acknowledge is sent again, as `TopicProducer`'s contract says and a
 * transport's bounded close rests on: a saga's command to another context and an intake's receipt, between the sales
 * and stock services of [CommandIntakeTest]. Each is held while the transport refuses it, its entry not checkpointed
 * past, and sent once the transport answers; or, its send never returning, it is sent by the next node from the
 * checkpoint after its node stopped.
 */
class UnacknowledgedSendsTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems()
    private val stockBackends = InMemoryBackends(PARTITIONS)
    private val salesBackends = InMemoryBackends(PARTITIONS)
    private val cancelled = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    /**
     * A producer over [topics] that does not acknowledge its sends until it [answers][answering]: each refused as the
     * transport being unavailable, or, [hanging], never returning, as a send to a broker that does not answer.
     */
    private inner class Unacknowledging(private val hanging: Boolean = false) : TopicProducer {
        @Volatile
        var answering = false

        /** The sends it did not acknowledge. */
        val refused = AtomicInteger()

        override suspend fun send(topic: String, messages: List<TopicMessage>) {
            if (!answering) {
                refused.incrementAndGet()
                if (hanging) awaitCancellation()
                throw TransportUnavailableException("The broker did not acknowledge the send.")
            }
            topics.send(topic, messages)
        }
    }

    private fun stockNode(name: String = "stock-1", producer: TopicProducer = topics): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        stockBackends,
        channel,
        options = fastNodeOptions(),
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(topics, PREFIX, producer, intake = true),
    ).also { register(it) }

    private fun salesNode(name: String = "sales-1", producer: TopicProducer = topics): ServiceNode = ServiceNode(
        SALES_SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest(),
        salesBackends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))),
        contracts = ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        topics = SourceTopics(topics, PREFIX, producer),
    ).also { register(it) }

    private fun register(node: ServiceNode) {
        nodes += node
        channel.bind(node.address, node.forwardHandler)
    }

    private suspend fun ServiceNode.stopNode() {
        stop()
        nodes -= this
    }

    private suspend fun ServiceNode.place(id: String, quantity: Int): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command("place-$id", "place-$id", ORDER.ref(TENANT, id), PlaceOrder(quantity), wait = WaitPlan.COMMITTED),
        )
    }

    /** The order saga's checkpoint in the partition of [ref]: the entries it passed there. */
    private suspend fun ServiceNode.sagaCheckpoint(ref: AggregateRef): Long =
        salesBackends.backends(SALES).checkpoints.load(
            context(SALES).group.name,
            SAGA,
            Partitioner.partitionOf(ref, PARTITIONS),
        ) ?: 0L

    /** The stock intake's checkpoint in the partition of [ref]: the commands it passed there. */
    private suspend fun ServiceNode.intakeCheckpoint(ref: AggregateRef): Long =
        stockBackends.backends(STOCK).checkpoints.load(
            context(STOCK).group.name,
            CommandIntake.NAME,
            Partitioner.partitionOf(ref, PARTITIONS),
        ) ?: 0L

    @Test
    fun `a saga's command the transport does not acknowledge should hold its entry until it is sent`(): Unit =
        runBlocking {
            stockNode().start()
            val producer = Unacknowledging()
            val sales = salesNode(producer = producer).also { it.start() }
            val order = ORDER.ref(TENANT, "o-1")
            sales.place("o-1", 2).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // Refused again and again: retried, the entry not passed, nothing sent.
            eventually { producer.refused.get() >= 2 }
            sales.sagaCheckpoint(order).assert().isZero()
            topics.messages(COMMANDS).assert().isEmpty()
            producer.answering = true
            eventually { stock.decided.size == 1 }
            eventually { sales.sagaCheckpoint(order) == 1L }
            topics.messages(COMMANDS).size.assert().isEqualTo(1)
        }

    @Test
    fun `a saga's command whose send never returned should be sent from the checkpoint by the next node`(): Unit =
        runBlocking {
            stockNode().start()
            val producer = Unacknowledging(hanging = true)
            val first = salesNode(producer = producer).also { it.start() }
            val order = ORDER.ref(TENANT, "o-2")
            first.place("o-2", 2).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { producer.refused.get() == 1 }
            // Stopped with its send in flight, as a node whose transport closed under it.
            first.stopNode()
            first.sagaCheckpoint(order).assert().isZero()
            val next = salesNode("sales-2").also { it.start() }
            eventually { stock.decided.size == 1 }
            eventually { next.sagaCheckpoint(order) == 1L }
            topics.messages(COMMANDS).size.assert().isEqualTo(1)
        }

    @Test
    fun `a receipt the transport does not acknowledge should hold its command until it is sent, handled once`(): Unit =
        runBlocking {
            val producer = Unacknowledging()
            val stockNode = stockNode(producer = producer).also { it.start() }
            val sales = salesNode().also { it.start() }
            val item = ITEM.ref(TENANT, "o-3")
            sales.place("o-3", AVAILABLE + 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // Rejected, and its receipt refused again and again: the command not passed, no receipt sent.
            eventually { producer.refused.get() >= 2 }
            stockNode.intakeCheckpoint(item).assert().isZero()
            topics.messages(RECEIPTS).assert().isEmpty()
            rejections.assert().isEmpty()
            producer.answering = true
            eventually { rejections.size == 1 }
            eventually { stockNode.intakeCheckpoint(item) == 1L }
            eventually { cancelled.size == 1 }
            topics.messages(RECEIPTS).size.assert().isEqualTo(1)
        }

    @Test
    fun `a receipt whose send never returned should be sent again by the next node, handled once`(): Unit =
        runBlocking {
            val producer = Unacknowledging(hanging = true)
            val first = stockNode(producer = producer).also { it.start() }
            val sales = salesNode().also { it.start() }
            val item = ITEM.ref(TENANT, "o-4")
            sales.place("o-4", AVAILABLE + 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { producer.refused.get() == 1 }
            first.stopNode()
            first.intakeCheckpoint(item).assert().isZero()
            rejections.assert().isEmpty()
            val next = stockNode("stock-2").also { it.start() }
            eventually { rejections.size == 1 }
            eventually { next.intakeCheckpoint(item) == 1L }
            eventually { cancelled.size == 1 }
            topics.messages(RECEIPTS).size.assert().isEqualTo(1)
        }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"

        /** The order saga's subscription: `OrderSaga.onOrderPlaced`, the name a reservation's reply carries. */
        const val SAGA = "OrderSaga.onOrderPlaced"
    }
}
