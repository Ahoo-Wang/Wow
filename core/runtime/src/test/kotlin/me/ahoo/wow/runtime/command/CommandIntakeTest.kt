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
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The command intake of a context and the receipts of its rejections (decisions D33, D47, D135–D137), between a sales
 * service whose saga reserves stock and a stock service, over [InMemoryTopics]: the saga's command reaches the stock
 * context's command topic in its log partition, the owner of that partition executes it once, its checkpoint passes it
 * only once its outcome is settled, a rejection's receipt is durable before that and comes back to the saga's
 * `@OnRejected` handler once, and a technical failure parks the aggregate at the command's offset.
 */
class CommandIntakeTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems()
    private val stockBackends = InMemoryBackends(PARTITIONS)
    private val salesBackends = InMemoryBackends(PARTITIONS)
    private val cancelled = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandRejected<*>>()

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
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun salesNode(): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest(),
        salesBackends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))),
        contracts = ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.place(id: String, quantity: Int): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command("place-$id", "place-$id", ORDER.ref(TENANT, id), PlaceOrder(quantity), wait = WaitPlan.COMMITTED),
        )
    }

    private val sender = CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))

    private suspend fun reserve(id: String, quantity: Int, item: String = "i-1"): Command<Reserve> =
        Command(id, "r-$id", ITEM.ref(TENANT, item), Reserve(quantity)).also { sender.send(it) }

    private suspend fun checkpoint(node: ServiceNode, ref: AggregateRef): Long =
        stockBackends.backends(STOCK).checkpoints.load(
            node.context(STOCK).group.name,
            CommandIntake.NAME,
            Partitioner.partitionOf(ref, PARTITIONS),
        ) ?: 0L

    @Test
    fun `a saga's command to another context should reach its command topic and be executed by its owner`(): Unit =
        runBlocking {
            val stockNode = stockNode().also { it.start() }
            val sales = salesNode().also { it.start() }
            sales.place("o-1", 2).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { stock.decided.size == 1 }
            val ref = ITEM.ref(TENANT, "o-1")
            val message = topics.messages(COMMANDS).single()
            message.key.assert().isEqualTo(ref.toString())
            val envelope = EnvelopeCodec.decode(message.value)
            envelope.kind.assert().isEqualTo(EnvelopeKind.COMMAND)
            envelope.type.assert().isEqualTo("stock.item.Reserve")
            val wire = CommandMessages.commandOf(envelope)
            wire.ref.assert().isEqualTo(ref)
            wire.replyTo!!.context.assert().isEqualTo(SALES)
            wire.replyTo!!.executionId().subscription.assert().isEqualTo("OrderSaga.onOrderPlaced")
            wire.replyTo!!.executionId().ref.assert().isEqualTo(ORDER.ref(TENANT, "o-1"))
            // In the log partition of the aggregate, among the topic's partitions, which are the log's.
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            topics.offsets(COMMANDS, partition).end.assert().isEqualTo(1L)
            eventually { checkpoint(stockNode, ref) == 1L }
            rejections.assert().isEmpty()
            topics.messages(RECEIPTS).assert().isEmpty()
        }

    @Test
    fun `a creation that names no item should take its id at the intake, the same for its request every time`(): Unit =
        runBlocking {
            val stockNode = stockNode().also { it.start() }
            val unassigned = ITEM.unassigned(TENANT)
            val reserve = Command("new-1", "r-new-1", unassigned, Reserve(1))
            sender.send(reserve)
            // Decided is not yet committed: the intake's checkpoint moves once the commit is in the log.
            eventually { checkpoint(stockNode, unassigned) == 1L }
            val created = checkNotNull(stockBackends.backends(STOCK).log.findByRequestId(ITEM, TENANT, "r-new-1"))
            created.ref.isAssigned.assert().isTrue()
            // Sent again (a saga that handled its commit again): the same request finds the item it created.
            sender.send(reserve.copyWith(id = "new-1-again"))
            eventually { checkpoint(stockNode, unassigned) == 2L }
            stock.decided.assert().containsExactly("new-1")
        }

    @Test
    fun `a rejected command should send a receipt that the saga's handler takes once`(): Unit = runBlocking {
        stockNode().start()
        val sales = salesNode().also { it.start() }
        sales.place("o-2", AVAILABLE + 1)
        eventually { cancelled.size == 1 }
        val receipt = topics.messages(RECEIPTS).single()
        val envelope = EnvelopeCodec.decode(receipt.value)
        envelope.kind.assert().isEqualTo(EnvelopeKind.COMMAND_RECEIPT)
        val rejected = CommandMessages.rejectedOf(envelope)
        rejected.error.code.assert().isEqualTo(INSUFFICIENT)
        rejected.commandType.assert().isEqualTo("stock.item.Reserve")
        rejected.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-2"))
        rejected.executionId.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-2"))
        receipt.key.assert().isEqualTo(ORDER.ref(TENANT, "o-2").toString())
        rejections.single().commandId.assert().isEqualTo(rejected.commandId)
        // A copy of the receipt is dropped by its command id.
        topics.send(RECEIPTS, receipt.let { TopicMessage(it.key!!, it.value, it.headers) })
        val group = sales.topicSubscriptions.getValue(RECEIPTS).group.name
        eventually {
            (0 until 2).sumOf {
                salesBackends.backends(SALES).checkpoints.load(group, Receipts.nameOf(SALES), it) ?: 0L
            } == 2L
        }
        rejections.size.assert().isEqualTo(1)
        cancelled.size.assert().isEqualTo(1)
    }

    @Test
    fun `the intake should not start unless its command topic has the log's partitions`(): Unit = runBlocking {
        val node = ServiceNode(
            STOCK_SERVICE,
            "stock-x",
            "stock-x.internal:9090",
            listOf(stock.model),
            stockManifest(),
            stockBackends,
            channel,
            options = fastNodeOptions(),
            topics = SourceTopics(topics, "mismatch.", topics, intake = true),
        )
        topics.create("mismatch.wow.stock.commands", 3)
        assertThrows<IllegalStateException> { runBlocking { node.start() } }.message.assert()
            .contains("has 3 partitions").contains("must have $PARTITIONS")
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        assertThrows<IllegalArgumentException> { SourceTopics(topics, intake = true) }
    }

    @Test
    fun `a command delivered twice should be decided once`(): Unit = runBlocking {
        val node = stockNode().also { it.start() }
        val command = reserve("c-1", 1)
        sender.send(command)
        val ref = command.ref
        eventually { checkpoint(node, ref) == 2L }
        stock.decided.assert().containsExactly("c-1")
    }

    @Test
    fun `the checkpoint should pass a command only once its outcome is settled`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        stock.gates["i-slow"] = gate
        val node = stockNode().also { it.start() }
        val slow = reserve("c-slow", 1, item = "i-slow")
        // Another aggregate in the same partition goes on meanwhile, but the checkpoint stays before the slow one.
        val other = generateSequence(0) { it + 1 }.map { "i-$it" }
            .first { Partitioner.partitionOf(ITEM.ref(TENANT, it), PARTITIONS) == Partitioner.partitionOf(slow.ref, PARTITIONS) }
        reserve("c-other", 1, item = other)
        eventually { "c-other" in stock.decided }
        delay(100.milliseconds)
        checkpoint(node, slow.ref).assert().isEqualTo(0L)
        gate.complete(Unit)
        eventually { checkpoint(node, slow.ref) == 2L }
        stock.decided.assert().containsExactlyInAnyOrder("c-slow", "c-other")
    }

    @Test
    fun `a receipt should be durable before the checkpoint passes the rejected command`(): Unit = runBlocking {
        val held = CompletableDeferred<Unit>()
        val producer = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                if (topic == RECEIPTS) {
                    held.await()
                }
                topics.send(topic, messages)
            }
        }
        val node = stockNode(producer = producer).also { it.start() }
        val command = Command(
            "c-r",
            "r-c-r",
            ITEM.ref(TENANT, "i-r"),
            Reserve(AVAILABLE + 1),
        )
        CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))
            .send(
                command,
                replyTo = me.ahoo.wow.runtime.cluster.ReplyTo(
                    SALES,
                    "OrderSaga.onOrderPlaced",
                    ORDER.ref(TENANT, "o"),
                    1
                )
            )
        eventually { "c-r" in stock.decided }
        delay(100.milliseconds)
        checkpoint(node, command.ref).assert().isEqualTo(0L)
        topics.messages(RECEIPTS).assert().isEmpty()
        held.complete(Unit)
        eventually { checkpoint(node, command.ref) == 1L }
        CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
            .commandId.assert().isEqualTo("c-r")
    }

    @Test
    fun `a crash between the receipt and the checkpoint should send the receipt again, handled once`(): Unit =
        runBlocking {
            val sales = salesNode().also { it.start() }
            // The first stock node never saves a checkpoint: it crashes after the receipt, before the save.
            val crashing = BackendFactory { context ->
                stockBackends.backends(context).copyWith(checkpoints = DroppedCheckpoints)
            }
            val first = stockNode("stock-1", backends = crashing).also { it.start() }
            sales.place("o-3", AVAILABLE + 1)
            eventually { rejections.size == 1 }
            first.stop()
            nodes -= first
            stockNode("stock-2").start()
            eventually { topics.messages(RECEIPTS).size == 2 }
            val (receipt, copy) = topics.messages(RECEIPTS).map { EnvelopeCodec.decode(it.value).id }
            copy.assert().isEqualTo(receipt)
            stock.decided.size.assert().isEqualTo(2)
            delay(100.milliseconds)
            rejections.size.assert().isEqualTo(1)
            cancelled.size.assert().isEqualTo(1)
        }

    @Test
    fun `a technical failure should park the aggregate at the command's offset until it is resumed`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val broken = reserve("c-broken", BREAKING, item = "i-b")
            val after = reserve("c-after", 1, item = "i-b")
            val elsewhere = reserve("c-elsewhere", 1, item = "i-e")
            val ledger = stockBackends.backends(STOCK).ledger
            eventually { ledger.isParked(CommandIntake.NAME, broken.ref) }
            val parked = ledger.parked(CommandIntake.NAME, broken.ref)!!
            parked.fromPosition.assert().isEqualTo(0L)
            parked.reason.assert().contains("unreachable")
            eventually { elsewhere.id in stock.decided }
            // The parked aggregate's later command waits; the checkpoint passes both.
            val end = topics.offsets(COMMANDS, Partitioner.partitionOf(broken.ref, PARTITIONS)).end
            eventually { checkpoint(node, broken.ref) == end }
            stock.decided.assert().doesNotContain(broken.id, after.id)
            stock.broken = false
            node.context(STOCK).intake!!.resume(CommandIntake.NAME, broken.ref).assert().isTrue()
            stock.decided.assert().contains(broken.id, after.id)
            ledger.isParked(CommandIntake.NAME, broken.ref).assert().isFalse()
            // A command of the aggregate after the resume runs as any other.
            val later = reserve("c-later", 1, item = "i-b")
            eventually { later.id in stock.decided }
        }

    @Test
    fun `a command the context does not know should park its aggregate`(): Unit = runBlocking {
        val node = stockNode().also { it.start() }
        val unknown = Command("c-u", "r-c-u", ITEM.ref(TENANT, "i-u"), Reserve(1))
        val wire = me.ahoo.wow.runtime.cluster.ClusterCodec.wire("stock.item.Unknown", unknown)
        val partition = Partitioner.partitionOf(unknown.ref, PARTITIONS)
        topics.send(COMMANDS, CommandMessages.command(wire, partition))
        // Not a command at all: passed over.
        topics.send(COMMANDS, TopicMessage(unknown.ref.toString(), "{}".toByteArray(), partition = partition))
        val ledger = stockBackends.backends(STOCK).ledger
        eventually { ledger.isParked(CommandIntake.NAME, unknown.ref) }
        ledger.parked(CommandIntake.NAME, unknown.ref)!!.reason.assert().contains("no command type stock.item.Unknown")
        // The checkpoint passes what was passed over with the next command it takes.
        val next = generateSequence(0) { it + 1 }.map { "i-$it" }
            .first { Partitioner.partitionOf(ITEM.ref(TENANT, it), PARTITIONS) == partition }
        reserve("c-next", 1, item = next)
        eventually { checkpoint(node, unknown.ref) == 3L }
    }

    @Test
    fun `a message of an aggregate that holds no command should park the aggregate there`(): Unit = runBlocking {
        // Review A3-14: an undecodable command is not dropped silently.
        val node = stockNode().also { it.start() }
        val ref = ITEM.ref(TENANT, "i-garbled")
        val partition = Partitioner.partitionOf(ref, PARTITIONS)
        topics.send(COMMANDS, TopicMessage(ref.toString(), "not an envelope".toByteArray(), partition = partition))
        val after = reserve("c-after-garbled", 1, item = "i-garbled")
        val ledger = stockBackends.backends(STOCK).ledger
        eventually { ledger.isParked(CommandIntake.NAME, ref) }
        ledger.parked(CommandIntake.NAME, ref)!!.reason.assert().contains("cannot be decoded")
        eventually { checkpoint(node, ref) == topics.offsets(COMMANDS, partition).end }
        stock.decided.assert().doesNotContain(after.id)
        // Skipped by an operator, the aggregate takes its commands again.
        node.context(STOCK).intake!!.skip(CommandIntake.NAME, ref).assert().isTrue()
        val later = reserve("c-later-garbled", 1, item = "i-garbled")
        eventually { later.id in stock.decided }
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
    }
}
