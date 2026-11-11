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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

/**
 * The command intake of a context and the receipts of its rejections (decisions D33, D47, D135–D137), between a sales
 * service whose saga reserves stock and a stock service, over [InMemoryTopics]: the saga's command reaches the stock
 * context's command topic in its log partition, the owner of that partition executes it once, its checkpoint passes it
 * only once its outcome is settled, a rejection's receipt is durable before that and comes back to the saga's
 * `@OnRejected` handler once, and a technical failure parks the aggregate at the command's offset.
 */
class CommandIntakeTest : CommandIntakeFixtures() {
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
        eventually { ledger.isParked(INTAKE, lane) }
        ledger.isParked(INTAKE, ITEM.unassigned(TENANT)).assert().isFalse()
        // Every other creation of the type and tenant goes on, whichever partition it lands in.
        val others = (1..4).map { Command("new-$it", "r-new-$it", ITEM.unassigned(TENANT), Reserve(1)) }
        others.forEach { sender.send(it) }
        eventually { stock.decided.containsAll(others.map { it.id }) }
        stock.decided.assert().doesNotContain(broken.id)
        others.map { Partitioner.partitionOf(ITEM.ref(TENANT, "(none):${it.requestId}"), PARTITIONS) }.toSet().size
            .assert().isGreaterThan(1)
    }

    @Test
    fun `a rejection of an existing aggregate should send its version back with the receipt`(): Unit = runBlocking {
        stockNode().start()
        // The item exists at version 1 before the saga's reservation is rejected on it.
        val created = reserve("c-o-3", 1, item = "o-3")
        eventually { created.id in stock.decided }
        val sales = salesNode().also { it.start() }
        sales.place("o-3", AVAILABLE + 1)
        eventually { cancelled.size == 1 }
        val rejected = CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
        rejected.error.code.assert().isEqualTo(INSUFFICIENT)
        rejected.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-3"))
        // The version the rejected decision saw, across the receipts topic, as the saga's handler got it.
        rejected.version.assert().isEqualTo(1)
        rejections.single().version.assert().isEqualTo(1)
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
        // The reservation would have created the item: a creation's rejection has no version.
        rejected.version.assert().isNull()
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
                    ledger.isParked(INTAKE, busy.ref).assert().isFalse()
                    stock.decided.assert().doesNotContain(busy.id)
                }
            } finally {
                // Never left holding the node's stop.
                held.forEach { it.complete(Unit) }
            }
            callers.forEach { it.join() }
            eventually { busy.id in stock.decided }
            eventually { checkpoint(node, busy.ref) == 1L }
            ledger.isParked(INTAKE, busy.ref).assert().isFalse()
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
            // Two commands hold the intakes' share while their gates do: the bound holds as long as they are held.
            val executor = node.context(STOCK).executor
            eventually { executor.commandsInFlight == 2 }
            executor.commandsInFlight.assert().isEqualTo(2)
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
            stockBackends.backends(STOCK).ledger.isParked(INTAKE, scheduled.ref).assert().isFalse()
            eventually { checkpoint(node, scheduled.ref) == 1L }
        }
}
