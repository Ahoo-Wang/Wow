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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
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
        options: ServiceNodeOptions = fastNodeOptions(),
    ): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        backends,
        channel,
        options = options,
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(topics, PREFIX, producer, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun salesNode(peer: ManifestFragment = peer(stockManifest())): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest(),
        salesBackends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))),
        contracts = ContractDirectory(salesManifest(), listOf(peer)),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.place(
        id: String,
        quantity: Int,
        headers: Map<String, String> = emptyMap(),
    ): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command(
                "place-$id",
                "place-$id",
                ORDER.ref(TENANT, id),
                PlaceOrder(quantity),
                headers = headers,
                wait = WaitPlan.COMMITTED,
            ),
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
            // Keyed and partitioned by its request (review R2.1-2).
            val lane = ITEM.ref(TENANT, "(none):r-new-1")
            topics.messages(COMMANDS).single().key.assert().isEqualTo(lane.toString())
            // Decided is not yet committed: the intake's checkpoint moves once the commit is in the log.
            eventually { checkpoint(stockNode, lane) == 1L }
            val created = checkNotNull(stockBackends.backends(STOCK).log.findByRequestId(ITEM, TENANT, "r-new-1"))
            created.ref.isAssigned.assert().isTrue()
            // Sent again (a saga that handled its commit again): the same request finds the item it created.
            sender.send(reserve.copyWith(id = "new-1-again"))
            eventually { checkpoint(stockNode, lane) == 2L }
            stock.decided.assert().containsExactly("new-1")
        }

    @Test
    fun `a creation that names no item and fails should park only its own request`(): Unit = runBlocking {
        // Review R2.1-2: the creations of one type and tenant do not share one lane nor one parking key.
        stockNode().also { it.start() }
        val broken = Command("new-b", "r-new-b", ITEM.unassigned(TENANT), Reserve(BREAKING)).also { sender.send(it) }
        val ledger = stockBackends.backends(STOCK).ledger
        val lane = ITEM.ref(TENANT, "(none):r-new-b")
        eventually { ledger.isParked(CommandIntake.NAME, lane) }
        ledger.isParked(CommandIntake.NAME, ITEM.unassigned(TENANT)).assert().isFalse()
        // Every other creation of the type and tenant goes on, whichever partition it lands in.
        val others = (1..4).map { Command("new-$it", "r-new-$it", ITEM.unassigned(TENANT), Reserve(1)) }
        others.forEach { sender.send(it) }
        eventually { stock.decided.containsAll(others.map { it.id }) }
        stock.decided.assert().doesNotContain(broken.id)
        others.map { Partitioner.partitionOf(ITEM.ref(TENANT, "(none):${it.requestId}"), PARTITIONS) }.toSet().size
            .assert().isGreaterThan(1)
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
        topics.send(RECEIPTS, receipt.let { TopicMessage.of(it.key!!, it.value, it.headers) })
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
    fun `a saga's command, its receipt and the compensation should carry only the facts that propagate`(): Unit =
        runBlocking {
            stockNode().start()
            val sales = salesNode().also { it.start() }
            val facts = mapOf(RequestFacts.APP_ID to "console", RequestFacts.REMOTE_IP to "10.0.0.7", "X-T" to "t")
            sales.place("o-3", AVAILABLE + 1, facts)
            // The decide function runs before the commit: wait for the compensation in the log, not for the decision.
            val log = salesBackends.backends(SALES).log
            eventually { log.read(ORDER.ref(TENANT, "o-3")).toList().lastOrNull()?.commandType == "sales.order.CancelOrder" }
            val propagated = mapOf(RequestFacts.APP_ID to "console")
            // Decision D232: the application goes along the chain, the client's address does not.
            CommandMessages.commandOf(EnvelopeCodec.decode(topics.messages(COMMANDS).single().value)).headers
                .assert().isEqualTo(propagated)
            CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value)).headers
                .assert().isEqualTo(propagated)
            rejections.single().headers.assert().isEqualTo(propagated)
            cancelled.size.assert().isEqualTo(1)
            val compensated = log.read(ORDER.ref(TENANT, "o-3")).toList().last()
            compensated.commandType.assert().isEqualTo("sales.order.CancelOrder")
            compensated.headers.assert().isEqualTo(propagated)
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
    fun `a command type the context does not know should be rejected, not park its aggregate`(): Unit =
        runBlocking {
            // Review X-5: a sender's mistake is its rejection; the aggregate it names takes its later commands.
            val node = stockNode().also { it.start() }
            val unknown = Command("c-u", "r-c-u", ITEM.ref(TENANT, "i-u"), Reserve(1))
            val partition = Partitioner.partitionOf(unknown.ref, PARTITIONS)
            val replyTo = ReplyTo(SALES, "OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-u"), 1)
            val wire = ClusterCodec.wire("stock.item.Unknown", unknown, replyTo)
            topics.send(COMMANDS, CommandMessages.command(wire, partition))
            val later = reserve("c-u-later", 1, item = "i-u")
            eventually { later.id in stock.decided }
            val ledger = stockBackends.backends(STOCK).ledger
            ledger.isParked(CommandIntake.NAME, unknown.ref).assert().isFalse()
            eventually { checkpoint(node, unknown.ref) == 2L }
            val rejected = CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
            rejected.commandId.assert().isEqualTo("c-u")
            rejected.commandType.assert().isEqualTo("stock.item.Unknown")
            rejected.ref.assert().isEqualTo(unknown.ref)
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
            rejected.error.message.assert().contains("no command type stock.item.Unknown")
            rejected.executionId.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-u"))
        }

    @Test
    fun `an aggregate type the context does not know should be rejected without a receipt when none is asked`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val ref = AggregateRef(AggregateType(STOCK, "shelf"), TENANT, "s-1")
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            val wire = ClusterCodec.wire(
                "stock.shelf.Reserve",
                Command("c-s", "r-c-s", ref, Reserve(1)),
            )
            topics.send(COMMANDS, CommandMessages.command(wire, partition))
            eventually { checkpoint(node, ref) == 1L }
            stockBackends.backends(STOCK).ledger.isParked(CommandIntake.NAME, ref).assert().isFalse()
            topics.messages(RECEIPTS).assert().isEmpty()
        }

    @Test
    fun `a saga's command of a type the other context does not know yet should come back as its rejection`(): Unit =
        runBlocking {
            // Review X-5: the sales service has a newer stock contract, which names the reservation ReserveV2.
            val stockNode = stockNode().also { it.start() }
            val sales = salesNode(peer = peer(renamed(stockManifest(), "Reserve", "ReserveV2"))).also { it.start() }
            sales.place("o-v2", 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // The saga's @OnRejected handler takes the rejection and cancels the order.
            eventually { cancelled.size == 1 }
            val rejected = rejections.single()
            rejected.commandType.assert().isEqualTo("stock.item.ReserveV2")
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
            rejected.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-v2"))
            stock.decided.assert().isEmpty()
            // The stock item is not parked: its later commands are executed.
            val item = ITEM.ref(TENANT, "o-v2")
            stockBackends.backends(STOCK).ledger.isParked(CommandIntake.NAME, item).assert().isFalse()
            val later = reserve("c-v2-later", 1, item = "o-v2")
            eventually { later.id in stock.decided }
            eventually { checkpoint(stockNode, item) == 2L }
        }

    @Test
    fun `a message of an aggregate that holds no command should park the aggregate there`(): Unit = runBlocking {
        // Review A3-14: an undecodable command is not dropped silently.
        val node = stockNode().also { it.start() }
        val ref = ITEM.ref(TENANT, "i-garbled")
        val partition = Partitioner.partitionOf(ref, PARTITIONS)
        topics.send(COMMANDS, TopicMessage.of(ref.toString(), "not an envelope".toByteArray(), partition = partition))
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

    @Test
    fun `a message that names no aggregate and holds no command should be passed over, parking nothing`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val ref = ITEM.ref(TENANT, "i-after-keyless")
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            topics.send(
                COMMANDS,
                TopicMessage.of("no aggregate", "not an envelope".toByteArray(), partition = partition)
            )
            val after = reserve("c-after-keyless", 1, item = "i-after-keyless")
            eventually { after.id in stock.decided }
            stockBackends.backends(STOCK).ledger.isParked(CommandIntake.NAME, ref).assert().isFalse()
            node.context(STOCK).intake.assert().isNotNull()
        }

    @Test
    fun `a command the node refuses as busy should wait for room instead of parking its aggregate`(): Unit =
        runBlocking {
            // Review R2.1-1: the node's in-flight bound is backpressure for the intake, not a technical failure.
            val node = stockNode(
                options = fastNodeOptions().copyWith(executor = ExecutorOptions(maxInFlight = 2)),
            ).also {
                it.start()
            }
            val context = node.context(STOCK)
            val items = listOf("i-h1", "i-h2")
            val held = items.map { item -> CompletableDeferred<Unit>().also { stock.gates[item] = it } }
            val callers = items.map { item ->
                launch {
                    context.coordinator.execute(
                        stock.model,
                        Command("h-$item", "h-$item", ITEM.ref(TENANT, item), Reserve(1), wait = WaitPlan.COMMITTED),
                    )
                }
            }
            val ledger = stockBackends.backends(STOCK).ledger
            val busy = try {
                eventually { context.executor.commandsInFlight == 2 }
                reserve("c-busy", 1, item = "i-busy").also { busy ->
                    // Far longer than the subscription's three attempts take.
                    delay(300.milliseconds)
                    ledger.isParked(CommandIntake.NAME, busy.ref).assert().isFalse()
                    stock.decided.assert().doesNotContain(busy.id)
                }
            } finally {
                // Never left holding the node's stop.
                held.forEach { it.complete(Unit) }
            }
            callers.forEach { it.join() }
            eventually { busy.id in stock.decided }
            eventually { checkpoint(node, busy.ref) == 1L }
            ledger.isParked(CommandIntake.NAME, busy.ref).assert().isFalse()
        }

    @Test
    fun `the intakes of a node should execute at most half its in-flight bound at once`(): Unit = runBlocking {
        // Review R2.1-1: a backlog over many partitions leaves room for callers, sagas and timers.
        InFlightLimit().intakes.availablePermits.assert().isEqualTo(InFlightLimit.DEFAULT_MAX / 2)
        InFlightLimit(1).intakes.availablePermits.assert().isEqualTo(1)
        val node = stockNode(options = fastNodeOptions().copyWith(executor = ExecutorOptions(maxInFlight = 4))).also {
            it.start()
        }
        val items = (1..4).map { "i-s$it" }
        val gates = items.map { item -> CompletableDeferred<Unit>().also { stock.gates[item] = it } }
        try {
            items.forEach { reserve("c-$it", 1, item = it) }
            delay(300.milliseconds)
            node.context(STOCK).executor.commandsInFlight.assert().isEqualTo(2)
        } finally {
            gates.forEach { it.complete(Unit) }
        }
        eventually { stock.decided.containsAll(items.map { "c-$it" }) }
    }

    @Test
    fun `a scheduled command early by the target's clock should wait for its due time, not park`(): Unit =
        runBlocking {
            // Review R2.5-1: nothing sends another context's timer command again once the broker took it.
            val defaults = fastNodeOptions()
            val timers = defaults.timers.copyWith(maxEarlyWait = 50.milliseconds)
            val node = stockNode(options = defaults.copyWith(timers = timers)).also { it.start() }
            val due = System.currentTimeMillis() + 1_000
            val slot = ScheduleContext("timer-1", "release", 1, due)
            val scheduled = Command("t-1", "timer-1#1", ITEM.ref(TENANT, "i-t"), Reserve(1), headers = slot.headers())
            sender.send(scheduled)
            eventually { scheduled.id in stock.decided }
            System.currentTimeMillis().assert().isGreaterThanOrEqualTo(due)
            stockBackends.backends(STOCK).ledger.isParked(CommandIntake.NAME, scheduled.ref).assert().isFalse()
            eventually { checkpoint(node, scheduled.ref) == 1L }
        }

    /** A checkpoint store that never saves: the node crashes before each save. */
    private object DroppedCheckpoints : CheckpointStore {
        override suspend fun load(group: String, subscription: String, partition: Int): Long? = null

        override suspend fun list(group: String): List<Checkpoint> = emptyList()

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) = Unit
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
    }
}
