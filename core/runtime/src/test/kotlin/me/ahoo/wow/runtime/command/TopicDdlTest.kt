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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicRequirement
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The topics a node's contexts own, validated or created at its start as the topic DDL says (decision D168): the
 * command topic of a context that takes commands, with exactly its log's partitions, the receipts topic of a context
 * whose sagas send commands to another context, and the topics of the streams it publishes.
 */
class TopicDdlTest {
    private val topics = InMemoryTopics(1)
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun stockNode(ddl: Ddl, intake: Boolean = true): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(StockItems().model),
        stockManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        options = fastNodeOptions(),
        topics = SourceTopics(topics, PREFIX, topics, intake = intake, admin = topics, ddl = ddl),
    ).also { nodes += it }

    private fun salesNode(ddl: Ddl, saga: ProcessorBinding<*> = orderSaga()): ServiceNode {
        val manifest = salesManifest().let { manifest ->
            manifest.copyWith(
                contexts = manifest.contexts.map {
                    it.copyWith(
                        published = listOf(PublishedManifest("order", PublishedManifest.defaultTopic(SALES, "order")))
                    )
                },
            )
        }
        return ServiceNode(
            SALES_SERVICE,
            "sales-1",
            "sales-1.internal:9090",
            listOf(orderModel()),
            manifest,
            InMemoryBackends(PARTITIONS),
            channel,
            options = fastNodeOptions(),
            subscriptions = SubscriptionBindings(mapOf(SALES to listOf(saga))),
            contracts = ContractDirectory(manifest, listOf(peer(stockManifest()))),
            topics = SourceTopics(topics, PREFIX, topics, admin = topics, ddl = ddl),
        ).also { nodes += it }
    }

    @Test
    fun `validate should refuse to start without the command topic`(): Unit = runBlocking {
        val node = stockNode(Ddl.VALIDATE)
        node.topicRequirements.assert().containsExactly(
            TopicRequirement(
                COMMANDS,
                PARTITIONS,
                TopicRequirement.Count.EXACT,
                "the command topic of context stock, with as many partitions as its log",
            ),
        )
        val refused = assertThrows<WowConfigurationException> { runBlocking { node.start() } }
        refused.key.assert().isEqualTo("wow.kafka.ddl")
        refused.value.assert().isEqualTo("validate")
        refused.message.assert().contains("$COMMANDS does not exist").contains("topic DDL auto")
        topics.partitions(setOf(COMMANDS)).assert().isEmpty()
    }

    @Test
    fun `validate should refuse a command topic of another partition count, and start with the right one`(): Unit =
        runBlocking {
            topics.create(COMMANDS, PARTITIONS - 1)
            assertThrows<IllegalStateException> { runBlocking { stockNode(Ddl.VALIDATE).start() } }.message
                .assert().contains("$COMMANDS has ${PARTITIONS - 1} partitions, not $PARTITIONS")
            nodes.clear()
            topics.grow(COMMANDS, PARTITIONS)
            stockNode(Ddl.VALIDATE).also { it.start() }.isReady.assert().isTrue()
        }

    @Test
    fun `auto should create the missing topics with the log's partitions, leaving the others as they are`(): Unit =
        runBlocking {
            topics.create(RECEIPTS, 1)
            val sales = salesNode(Ddl.AUTO)
            sales.topicRequirements.map { it.topic }.assert().containsExactly(RECEIPTS, ORDERS)
            sales.start()
            topics.partitions(setOf(RECEIPTS, ORDERS)).assert().isEqualTo(mapOf(RECEIPTS to 1, ORDERS to PARTITIONS))
            stockNode(Ddl.AUTO).start()
            topics.partitions(setOf(COMMANDS)).assert().isEqualTo(mapOf(COMMANDS to PARTITIONS))
        }

    @Test
    fun `auto should not alter a command topic of another partition count`(): Unit = runBlocking {
        topics.create(COMMANDS, 2)
        assertThrows<IllegalStateException> { runBlocking { stockNode(Ddl.AUTO).start() } }.message.assert()
            .contains("$COMMANDS has 2 partitions, not $PARTITIONS").contains("never altered")
    }

    @Test
    fun `none should create nothing, and a node without an intake should need no command topic`(): Unit =
        runBlocking {
            // A published stream's topic is checked whatever the DDL, as a command topic is (#6783): it is missing.
            assertThrows<IllegalStateException> { runBlocking { salesNode(Ddl.NONE).start() } }
                .message.assert().startsWith("The topic $ORDERS does not exist")
            topics.partitions(setOf(ORDERS)).assert().isEmpty()
            val stock = stockNode(Ddl.VALIDATE, intake = false)
            stock.topicRequirements.assert().isEmpty()
            stock.start()
            stock.isReady.assert().isTrue()
        }

    @Test
    fun `a published stream's topic with fewer partitions than the log should stop the node, whatever the DDL`():
        Unit = runBlocking {
        // #6783: the topic sink sends each record to its aggregate's partition in the log; with fewer there is none.
        topics.create(ORDERS, PARTITIONS / 2)
        topics.create(COMMANDS, PARTITIONS)
        for (ddl in listOf(Ddl.NONE, Ddl.VALIDATE, Ddl.AUTO)) {
            assertThrows<Exception> { runBlocking { salesNode(ddl).start() } }.message.assert()
                .contains("$ORDERS has ${PARTITIONS / 2} partitions")
        }
        // More than the log's is taken: the added partitions stay empty.
        topics.grow(ORDERS, PARTITIONS * 2)
        salesNode(Ddl.VALIDATE).start()
    }

    @Test
    fun `validate should check the command topic of each peer context, which the node never creates (M-1)`(): Unit =
        runBlocking {
            topics.create(RECEIPTS, 1)
            topics.create(ORDERS, PARTITIONS)
            val sales = salesNode(Ddl.VALIDATE)
            sales.topicRequirements.last().assert().isEqualTo(
                TopicRequirement(
                    COMMANDS,
                    1,
                    TopicRequirement.Count.ANY,
                    "the command topic of peer context stock, where this node sends its commands"
                ),
            )
            assertThrows<IllegalStateException> { runBlocking { sales.start() } }.message.assert()
                .contains("$COMMANDS does not exist")
            nodes.clear()
            // Under auto, the peer creates it: the node neither creates it nor waits for it.
            salesNode(Ddl.AUTO).topicRequirements.map { it.topic }.assert().doesNotContain(COMMANDS)
            nodes.clear()
            topics.create(COMMANDS, PARTITIONS)
            salesNode(Ddl.VALIDATE).also { it.start() }.isReady.assert().isTrue()
        }

    @Test
    fun `validate should refuse to start without the receipts topic of a saga that sends to another context`(): Unit =
        runBlocking {
            // No @OnRejected or @OnCommitted handler: a rejection of its reservation still comes back, and parks.
            val reserving = sagaDeriving(Reserve::class.java) { ITEM.ref(it.tenantId, it.id) to Reserve(1) }
            val sales = salesNode(Ddl.VALIDATE, reserving)
            sales.topicRequirements.map { it.topic }.assert().containsExactly(RECEIPTS, ORDERS, COMMANDS)
            val refused = assertThrows<WowConfigurationException> { runBlocking { sales.start() } }
            refused.message.assert().contains(
                "The topics of node sales-1 are not as it needs them (topic DDL validate): $RECEIPTS does not exist " +
                    "(the receipts topic of context sales)",
            )
        }

    @Test
    fun `a saga that derives only its own context's commands should need no receipts topic`() {
        salesNode(Ddl.VALIDATE, sagaDeriving(CancelOrder::class.java) { it to CancelOrder() })
            .topicRequirements.map { it.topic }.assert().containsExactly(ORDERS, COMMANDS)
    }

    @Test
    fun `a saga that derives a lifecycle command, or schedules another context's, should need its receipts topic`() {
        // Every aggregate decides DeleteAggregate, the sales order too: it may go to any context's aggregate.
        salesNode(Ddl.VALIDATE, sagaDeriving(DeleteAggregate::class.java) { it to DeleteAggregate() })
            .topicRequirements.map { it.topic }.assert().containsExactly(RECEIPTS, ORDERS, COMMANDS)
        nodes.clear()
        val scheduling = ProcessorBinding.saga(
            "OrderSaga",
            "onOrderPlaced",
            OrderState::class.java,
            schedules = listOf(Reserve::class.java),
        ) { _, _ -> emptyList() }
        salesNode(Ddl.VALIDATE, scheduling).topicRequirements.map { it.topic }.assert()
            .containsExactly(RECEIPTS, ORDERS, COMMANDS)
    }

    /** An order saga with no receipt handler, deriving one command of [type] for each commit, as [request] makes it. */
    private fun <C : Any> sagaDeriving(type: Class<C>, request: (AggregateRef) -> Pair<AggregateRef, C>) =
        ProcessorBinding.saga(
            "OrderSaga",
            "onOrderPlaced",
            OrderState::class.java,
            derives = listOf(type),
        ) { event, _ ->
            val (to, command) = request(event.ref)
            listOf(CommandRequest.to(to, command))
        }

    @Test
    fun `a topic DDL other than none should need an admin`() {
        assertThrows<IllegalArgumentException> { SourceTopics(topics, ddl = Ddl.VALIDATE) }
        assertThrows<IllegalArgumentException> { TopicRequirement("t", 0, TopicRequirement.Count.ANY, "nothing") }
        SourceTopics(topics).ddl.assert().isEqualTo(Ddl.NONE)
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
        const val ORDERS = PREFIX + "wow.sales.order.state"
    }
}
