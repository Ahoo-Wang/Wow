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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The success receipts of commands a saga sends to another context (decision D192), between a sales service whose
 * saga reserves stock and confirms the order once the reservation is committed, and a stock service, over
 * [InMemoryTopics]: the target sends the receipt on commit, durable before its checkpoint passes the command; a crash
 * in between sends it again, and the saga handles it once; a duplicate request answers with the original aggregate;
 * a command whose saga has no `@OnCommitted` handler asks for none; a rejection still comes back as before.
 */
class CommittedReceiptsTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems()
    private val stockBackends = InMemoryBackends(PARTITIONS)
    private val salesBackends = InMemoryBackends(PARTITIONS)
    private val cancelled = CopyOnWriteArrayList<String>()
    private val confirmed = CopyOnWriteArrayList<String>()
    private val commits = CopyOnWriteArrayList<CommandCommitted<*>>()
    private val rejections = CopyOnWriteArrayList<CommandRejected<*>>()

    @Volatile
    private var refuseConfirmation = false

    /** Each node's signals to another node go straight to that node's waits, as the internal signal entry would. */
    private val signals = SignalSender { origin: Origin, signal: WaitSignal, _ ->
        nodes.firstOrNull { it.node == origin.node }?.waits?.signal(signal)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun stockNode(
        name: String = "stock-1",
        producer: TopicProducer = topics,
        backends: BackendFactory = stockBackends,
    ): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        backends,
        channel,
        options = fastNodeOptions(),
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(topics, PREFIX, producer, intake = true),
        remoteSignals = signals,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun salesNode(): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled, confirmed) { !refuseConfirmation }),
        salesManifest(),
        salesBackends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(committingOrderSaga(commits, rejections)))),
        contracts = ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        topics = SourceTopics(topics, PREFIX, topics),
        remoteSignals = signals,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.place(id: String, quantity: Int, wait: WaitPlan = WaitPlan.COMMITTED): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command("place-$id", "place-$id", ORDER.ref(TENANT, id), PlaceOrder(quantity), wait = wait),
        )
    }

    private suspend fun checkpoint(ref: AggregateRef): Long =
        stockBackends.backends(STOCK).checkpoints.load(
            stockNode.context(STOCK).group.name,
            CommandIntake.NAME,
            Partitioner.partitionOf(ref, PARTITIONS),
        ) ?: 0L

    private lateinit var stockNode: ServiceNode

    private fun receipts(): List<Any> =
        topics.messages(RECEIPTS).map { CommandMessages.receiptOf(EnvelopeCodec.decode(it.value)) }

    private val replyTo = ReplyTo(SALES, "OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-r"), 1, committed = true)

    private suspend fun send(command: Command<*>, reply: ReplyTo? = replyTo) =
        CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))
            .send(command, replyTo = reply)

    @Test
    fun `a committed command should send its receipt, which the saga's @OnCommitted handler takes once`(): Unit =
        runBlocking {
            stockNode = stockNode().also { it.start() }
            val sales = salesNode().also { it.start() }
            sales.place("o-1", 2).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { confirmed.size == 1 }
            val wire = CommandMessages.commandOf(EnvelopeCodec.decode(topics.messages(COMMANDS).single().value))
            wire.replyTo!!.committed.assert().isTrue()
            val receipt = topics.messages(RECEIPTS).single()
            receipt.key.assert().isEqualTo(ORDER.ref(TENANT, "o-1").toString())
            val committed = receipts().single() as CommandCommitted<*>
            committed.commandId.assert().isEqualTo(wire.id)
            committed.commandType.assert().isEqualTo("stock.item.Reserve")
            committed.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-1"))
            committed.version.assert().isEqualTo(1)
            committed.result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            committed.result.duplicate.assert().isFalse()
            committed.executionId.assert().isEqualTo(
                ExecutionId("OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-1"), 1)
            )
            commits.single().assert().isEqualTo(committed)
            rejections.assert().isEmpty()
            // A copy of the receipt is dropped by its command id.
            topics.send(RECEIPTS, receipt.let { TopicMessage(it.key!!, it.value, it.headers) })
            val group = sales.topicSubscriptions.getValue(RECEIPTS).group.name
            eventually {
                (0 until 2).sumOf {
                    salesBackends.backends(SALES).checkpoints.load(group, Receipts.nameOf(SALES), it) ?: 0L
                } == 2L
            }
            commits.size.assert().isEqualTo(1)
            confirmed.size.assert().isEqualTo(1)
        }

    @Test
    fun `a wait should go on through the @OnCommitted handler's hop into the commands it derives`(): Unit = runBlocking {
        stockNode = stockNode().also { it.start() }
        val sales = salesNode().also { it.start() }
        val chain = WaitPlan.of(
            Hop.committed(),
            Hop.handled(SAGA),
            Hop.committed(STOCK),
            Hop.handled(ON_RESERVED, SALES),
            Hop.committed(SALES),
        )
        val result = sales.place("o-w", 1, chain)
        // The wait ends where the order's confirmation, which the receipt's handler derived, is committed.
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed(SALES))
        result.function.name.assert().isEqualTo("ConfirmOrder")
        result.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-w"))
        confirmed.single().assert().isEqualTo(result.commandId)
        // The receipt carried the rest of the chain, past the reservation's commit.
        val receipt = EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value)
        receipt.transport!!.wait!!.remaining.assert().containsExactly(
            Hop.handled(ON_RESERVED, SALES),
            Hop.committed(SALES)
        )
    }

    @Test
    fun `a wait through the @OnCommitted handler's hop should end REJECTED when what it derives is rejected`(): Unit =
        runBlocking {
            stockNode = stockNode().also { it.start() }
            val sales = salesNode().also { it.start() }
            // The order is cancelled before its reservation's receipt confirms it: the confirmation is rejected.
            refuseConfirmation = true
            val chain = WaitPlan.of(
                Hop.committed(),
                Hop.handled(SAGA),
                Hop.committed(STOCK),
                Hop.handled(ON_RESERVED, SALES),
                Hop.committed(SALES),
            )
            val result = sales.place("o-x", 1, chain)
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.hop.assert().isEqualTo(Hop.handled(ON_RESERVED, SALES))
            result.error!!.code.assert().isEqualTo(REFUSED)
        }

    @Test
    fun `a rejection should still come back as a rejection receipt, with no success receipt`(): Unit = runBlocking {
        stockNode = stockNode().also { it.start() }
        val sales = salesNode().also { it.start() }
        sales.place("o-2", AVAILABLE + 1)
        eventually { cancelled.size == 1 }
        (receipts().single() as CommandRejected<*>).error.code.assert().isEqualTo(INSUFFICIENT)
        rejections.size.assert().isEqualTo(1)
        commits.assert().isEmpty()
        confirmed.assert().isEmpty()
    }

    @Test
    fun `a command that asks for no success receipt should get none`(): Unit = runBlocking {
        stockNode = stockNode().also { it.start() }
        val committed = Command("c-n", "r-c-n", ITEM.ref(TENANT, "i-n"), Reserve(1))
        send(committed, replyTo.copy(committed = false))
        send(committed.copyWith(id = "c-none", requestId = "r-c-none"), reply = null)
        eventually { checkpoint(committed.ref) == 2L }
        stock.decided.assert().containsExactly("c-n", "c-none")
        topics.messages(RECEIPTS).assert().isEmpty()
    }

    @Test
    fun `a success receipt should be durable before the checkpoint passes the committed command`(): Unit =
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val producer = object : TopicProducer {
                override suspend fun send(topic: String, messages: List<TopicMessage>) {
                    if (topic == RECEIPTS) {
                        held.await()
                    }
                    topics.send(topic, messages)
                }
            }
            stockNode = stockNode(producer = producer).also { it.start() }
            val command = Command("c-d", "r-c-d", ITEM.ref(TENANT, "i-d"), Reserve(1))
            send(command)
            eventually { "c-d" in stock.decided }
            delay(100.milliseconds)
            checkpoint(command.ref).assert().isEqualTo(0L)
            topics.messages(RECEIPTS).assert().isEmpty()
            held.complete(Unit)
            eventually { checkpoint(command.ref) == 1L }
            (receipts().single() as CommandCommitted<*>).commandId.assert().isEqualTo("c-d")
        }

    @Test
    fun `a crash between the receipt and the checkpoint should send it again, the same commit, handled once`(): Unit =
        runBlocking {
            val sales = salesNode().also { it.start() }
            // The first stock node never saves a checkpoint: it crashes after the receipt, before the save.
            val crashing = BackendFactory { context ->
                stockBackends.backends(context).copyWith(checkpoints = DroppedCheckpoints)
            }
            val first = stockNode("stock-1", backends = crashing).also { it.start() }
            sales.place("o-3", 1)
            eventually { commits.size == 1 }
            first.stop()
            nodes -= first
            stockNode = stockNode("stock-2").also { it.start() }
            eventually { topics.messages(RECEIPTS).size == 2 }
            val (receipt, copy) = receipts().map { it as CommandCommitted<*> }
            copy.commandId.assert().isEqualTo(receipt.commandId)
            copy.ref.assert().isEqualTo(receipt.ref)
            copy.version.assert().isEqualTo(receipt.version)
            // Delivered again, the command is not decided again: its receipt is the original commit's.
            copy.result.duplicate.assert().isTrue()
            stock.decided.size.assert().isEqualTo(1)
            delay(100.milliseconds)
            commits.size.assert().isEqualTo(1)
            confirmed.size.assert().isEqualTo(1)
        }

    @Test
    fun `a duplicate request should send the receipt again with the aggregate id it was given`(): Unit = runBlocking {
        stockNode = stockNode().also { it.start() }
        val unassigned = ITEM.unassigned(TENANT)
        val creation = Command("new-1", "r-new-1", unassigned, Reserve(1))
        send(creation)
        // Its partition is its request's (review R2.1-2).
        val lane = ITEM.ref(TENANT, "(none):r-new-1")
        eventually { checkpoint(lane) == 1L }
        // The same request again (a saga that handled its commit again): the same id, decided once.
        send(creation)
        eventually { checkpoint(lane) == 2L }
        val (first, again) = receipts().map { it as CommandCommitted<*> }
        first.ref.isAssigned.assert().isTrue()
        again.ref.assert().isEqualTo(first.ref)
        again.aggregateId.assert().isEqualTo(first.aggregateId)
        again.version.assert().isEqualTo(first.version)
        first.result.duplicate.assert().isFalse()
        again.result.duplicate.assert().isTrue()
        again.commandId.assert().isEqualTo(first.commandId)
        stock.decided.assert().containsExactly("new-1")
    }

    /** A checkpoint store that never saves: the node crashes before each save. */
    private object DroppedCheckpoints : CheckpointStore {
        override suspend fun load(group: String, subscription: String, partition: Int): Long? = null

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) = Unit
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
        const val SAGA = "OrderSaga.onOrderPlaced"
        const val ON_RESERVED = "OrderSaga.onReserved"
    }
}
