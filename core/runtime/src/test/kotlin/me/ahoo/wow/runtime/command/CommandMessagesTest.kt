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

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.UnrecoverableException
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeHeaders
import me.ahoo.wow.spi.transport.EnvelopeKind
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The wire forms of the command and receipts topics (design 08 §8), how a command is named and partitioned when it is
 * sent (decisions D135, D157), and how their entries are read, resumed and handed to a saga's handler.
 */
class CommandMessagesTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val ref = ITEM.ref(TENANT, "i-1")
    private val execution = ExecutionId("OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-1"), 3)
    private val wait = WaitContext(
        "w-1",
        Origin("sales-1", "sales-1:9090", SALES_SERVICE),
        listOf(Hop.committed(STOCK)),
        99
    )

    data class Priced(val price: BigDecimal)

    @Test
    fun `a command should travel in its envelope with its wait context and the reply it asks for`() {
        val replyTo = ReplyTo.of(SALES, execution)
        val wire = ClusterCodec.wire(
            "stock.item.Reserve",
            Command(
                "c-1",
                "r-1",
                ref,
                Priced(BigDecimal("2.50")),
                causation = Causation("root", "o-1@3"),
                operator = Operator("alice", "t0", crossScope = true),
            ),
            replyTo,
        )
        val message = CommandMessages.command(wire, 3, wait)
        message.partition.assert().isEqualTo(3)
        message.key.assert().isEqualTo(ref.toString())
        message.headers[EnvelopeHeaders.KIND].assert().isEqualTo("command")
        message.headers[EnvelopeHeaders.TYPE].assert().isEqualTo("stock.item.Reserve")
        val envelope = EnvelopeCodec.decode(message.value)
        envelope.id.assert().isEqualTo("c-1")
        envelope.transport!!.wait.assert().isEqualTo(wait)
        val read = CommandMessages.commandOf(envelope)
        read.assert().isEqualTo(wire)
        read.operator.assert().isEqualTo(Operator("alice", "t0", crossScope = true))
        read.body.toString().assert().isEqualTo("""{"price":2.50}""")
        read.replyTo!!.executionId().assert().isEqualTo(execution)
        assertThrows<IllegalArgumentException> { CommandMessages.rejectedOf(envelope) }
        // Without a reply, the field is absent from what older readers see as an unknown field.
        CommandMessages.commandOf(EnvelopeCodec.decode(CommandMessages.command(wire.copyWith(replyTo = null), 0).value))
            .replyTo.assert().isNull()
        // The trace it is sent in goes with it (decision D174), without a wait context or with one; neither, nothing.
        val trace = mapOf("traceparent" to "00-1-2-01")
        EnvelopeCodec.decode(CommandMessages.command(wire, 0, trace = trace).value).transport.assert()
            .isEqualTo(Transport(trace = trace))
        EnvelopeCodec.decode(CommandMessages.command(wire, 0, wait, trace).value).transport.assert()
            .isEqualTo(Transport(wait, trace = trace))
        EnvelopeCodec.decode(CommandMessages.command(wire, 0).value).transport.assert().isNull()
    }

    @Test
    fun `a receipt should carry the rejection without the command's body`() {
        val rejected = CommandRejected<Any>(
            "c-1",
            "stock.item.Reserve",
            ref,
            ErrorInfo(INSUFFICIENT, "Only 5 left."),
            Causation("root", "o-1@3"),
            execution,
            Operator("alice", "t0"),
            Scope("alice", "space-a"),
            mapOf(RequestFacts.APP_ID to "console"),
            version = 7,
        )
        val message = CommandMessages.receipt(rejected)
        message.key.assert().isEqualTo(execution.ref.toString())
        message.partition.assert().isNull()
        val envelope = EnvelopeCodec.decode(message.value)
        envelope.kind.assert().isEqualTo(EnvelopeKind.COMMAND_RECEIPT)
        envelope.id.assert().isEqualTo("c-1")
        CommandMessages.rejectedOf(envelope).assert().isEqualTo(rejected)
        assertThrows<IllegalArgumentException> { CommandMessages.commandOf(envelope) }
        envelope.transport.assert().isNull()
        // The trace of the rejected command's execution goes back with its receipt (decision D174).
        val trace = mapOf("traceparent" to "00-1-2-01")
        EnvelopeCodec.decode(CommandMessages.receipt(rejected, trace).value).transport.assert()
            .isEqualTo(Transport(trace = trace))
    }

    private fun committed(commandId: String = "c-1", duplicate: Boolean = false): CommandCommitted<Any> {
        val item = ITEM.ref(TENANT, "i-9")
        return CommandCommitted(
            commandId = commandId,
            commandType = "stock.item.Reserve",
            ref = item,
            version = 2,
            result = CommandResult(
                commandId = commandId,
                requestId = "r-$commandId",
                correlationId = "root",
                ref = item,
                hop = Hop.committed(STOCK),
                function = FunctionInfo(FunctionKind.COMMAND, STOCK, "item", "Reserve"),
                status = CommandResult.Status.SUCCEEDED,
                version = 2,
                duplicate = duplicate,
                results = mapOf("reserved" to 2),
                signalTime = 7L,
            ),
            causation = Causation("root", "o-1@3"),
            executionId = execution,
            operator = Operator("alice", "t0"),
            scope = Scope("alice", "space-a"),
            headers = mapOf(RequestFacts.APP_ID to "console"),
        )
    }

    @Test
    fun `a success receipt should carry the aggregate, its version and the command's result, and no state`() {
        val committed = committed()
        val message = CommandMessages.receipt(committed)
        message.key.assert().isEqualTo(execution.ref.toString())
        val envelope = EnvelopeCodec.decode(message.value)
        envelope.kind.assert().isEqualTo(EnvelopeKind.COMMAND_RECEIPT)
        envelope.id.assert().isEqualTo("c-1")
        envelope.type.assert().isEqualTo("stock.item.Reserve")
        envelope.payload.has("state").assert().isFalse()
        envelope.payload.has("error").assert().isFalse()
        CommandMessages.committedOf(envelope).assert().isEqualTo(committed)
        CommandMessages.receiptOf(envelope).assert().isEqualTo(committed)
        envelope.transport.assert().isNull()
        // It goes on in the trace the command was executed in (decision D174).
        val trace = mapOf("traceparent" to "00-1-2-01")
        EnvelopeCodec.decode(CommandMessages.receipt(committed, trace).value).transport.assert()
            .isEqualTo(Transport(trace = trace))
        val rejection = EnvelopeCodec.decode(
            CommandMessages.receipt(
                CommandRejected<Any>(
                    "c-2",
                    "stock.item.Reserve",
                    ref,
                    ErrorInfo("e", "m"),
                    Causation("a", "b"),
                    execution
                ),
            ).value,
        )
        (CommandMessages.receiptOf(rejection) as CommandRejected<*>).commandId.assert().isEqualTo("c-2")
        val command = EnvelopeCodec.decode(
            CommandMessages.command(
                ClusterCodec.wire("stock.item.Reserve", Command("c", "c", ref, Reserve(1))),
                0
            ).value,
        )
        assertThrows<IllegalArgumentException> { CommandMessages.committedOf(command) }
        assertThrows<IllegalArgumentException> { CommandMessages.receiptOf(command) }
    }

    @Test
    fun `a receipt written before decision D232 carries no request facts`() {
        listOf(CommandMessages.receipt(committed()), CommandMessages.receipt(committed().toRejected())).forEach {
            val envelope = EnvelopeCodec.decode(it.value)
            (envelope.payload as tools.jackson.databind.node.ObjectNode).remove("headers")
            when (val receipt = CommandMessages.receiptOf(envelope)) {
                is CommandCommitted<*> -> receipt.headers.assert().isEmpty()
                else -> (receipt as CommandRejected<*>).headers.assert().isEmpty()
            }
        }
    }

    private fun CommandCommitted<Any>.toRejected(): CommandRejected<Any> =
        CommandRejected(commandId, commandType, ref, ErrorInfo("e", "m"), causation, executionId, headers = headers)

    @Test
    fun `a reply should ask for the success receipt only when it says so, false for an older writer`() {
        val asking = ReplyTo.of(SALES, execution, committed = true)
        asking.committed.assert().isTrue()
        ReplyTo.of(SALES, execution).committed.assert().isFalse()
        val wire = ClusterCodec.wire("stock.item.Reserve", Command("c-1", "r-1", ref, Reserve(1)), asking)
        CommandMessages.commandOf(EnvelopeCodec.decode(CommandMessages.command(wire, 0).value)).replyTo
            .assert().isEqualTo(asking)
        // A reply written before decision D192 has no flag: it asks for the receipt of a rejection only.
        val tree = ClusterCodec.mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(asking)
        tree.remove("committed")
        val older = ClusterCodec.mapper.treeToValue(tree, ReplyTo::class.java)
        older.committed.assert().isFalse()
        older.executionId().assert().isEqualTo(execution)
    }

    @Test
    fun `the entries of a receipts topic should hold both kinds of receipts in the saga aggregate's lane`(): Unit =
        runBlocking {
            topics.create(RECEIPTS, 1)
            val rejected = CommandRejected<Any>(
                "c-2",
                "stock.item.Reserve",
                ref,
                ErrorInfo("e", "m"),
                Causation("a", "b"),
                execution
            )
            topics.send(RECEIPTS, listOf(CommandMessages.receipt(committed()), CommandMessages.receipt(rejected)))
            val entries = MessageEntries.receipts(RECEIPTS, topics, 1).tail(0, null).take(2).toList()
            entries.map { it.message }.assert().containsExactly(committed(), rejected)
            entries.forEach {
                it.stream.ref.assert().isEqualTo(execution.ref)
                it.stream.scope.assert().isEqualTo(Scope.NONE)
            }
            entries.map { it.stream.commandId }.assert().containsExactly("c-1", "c-2")
            entries.first().stream.causation.assert().isEqualTo(Causation("root", "o-1@3"))
        }

    @Test
    fun `a success receipt should reach the handler once per command id with the command's class`(): Unit =
        runBlocking {
            val handled = CopyOnWriteArrayList<Pair<String, Class<*>>>()
            var answer: List<String>? = listOf("confirmed")
            val receipts = Receipts(
                SALES,
                ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
                receiptHandler(committed = { committed, command ->
                    handled += committed.commandId to command
                    answer
                }),
            )
            receipts.handle(committed())
            // A copy, or the duplicate request's receipt, is dropped by the command id.
            receipts.handle(committed(duplicate = true))
            handled.assert().containsExactly("c-1" to Reserve::class.java)
            // A rejection of the same command is dropped too: one receipt per command id.
            receipts.handle(
                CommandRejected<Any>(
                    "c-1",
                    "stock.item.Reserve",
                    ref,
                    ErrorInfo("e", "m"),
                    Causation("a", "b"),
                    execution
                )
            )
            handled.size.assert().isEqualTo(1)
            // No handler takes it: logged and passed over, once.
            answer = null
            receipts.handle(committed("c-2"))
            receipts.handle(committed("c-2"))
            handled.size.assert().isEqualTo(2)
            assertThrows<IllegalStateException> {
                runBlocking { receipts.handle(committed("c-3").copyWith(commandType = "stock.item.Gone")) }
            }.message.assert().contains("stock.item.Gone")
        }

    @Test
    fun `a command should be sent under the type its target's Manifest gives its class, in its log partition`(): Unit =
        runBlocking {
            val sender = CommandSender(
                ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
                SourceTopics(topics, PREFIX, topics),
            )
            sender.typeOf(Command("c", "r", ref, Reserve(1))).assert().isEqualTo("stock.item.Reserve")
            // A system command is every aggregate's, by its system name (decision D198).
            sender.typeOf(Command("c", "r", ref, CancelSchedule("k"))).assert().isEqualTo(Schedules.CANCEL)
            sender.typeOf(Command("c", "r", ref, DeleteAggregate)).assert().isEqualTo(Lifecycle.DELETE_AGGREGATE)
            assertThrows<IllegalStateException> { sender.typeOf(Command("c", "r", ref, "not a command")) }
                .message.assert().contains("depend on the api module of stock")
            val wrong = AggregateType(STOCK, "warehouse").ref(TENANT, "w")
            assertThrows<IllegalStateException> { sender.typeOf(Command("c", "r", wrong, Reserve(1))) }
                .message.assert().contains("not one of stock.warehouse")
            sender.send(Command("c-1", "r-1", ref, Reserve(1)), wait)
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            topics.offsets(COMMANDS, partition).end.assert().isEqualTo(1L)
            // A context with no command topic yet: InMemoryTopics creates topics on first use, so test the check itself.
            assertThrows<IllegalStateException> {
                runBlocking { CommandIntake.checkPartitions(COMMANDS, topics, STOCK, PARTITIONS + 1) }
            }.message.assert().contains("has $PARTITIONS partitions")
            CommandIntake.checkPartitions(COMMANDS, topics, STOCK, PARTITIONS)
            assertThrows<IllegalArgumentException> {
                CommandSender(
                    ContractDirectory(stockManifest()),
                    SourceTopics(topics)
                )
            }
        }

    @Test
    fun `the entries of a command topic should be ordered, parked and resumed by offset`(): Unit = runBlocking {
        val entries = MessageEntries.commands(COMMANDS, topics, PARTITIONS)
        val partition = Partitioner.partitionOf(ref, PARTITIONS)
        val wire: (String) -> CommandWire = { id ->
            ClusterCodec.wire(
                "stock.item.Reserve",
                Command(id, id, ref, Reserve(1))
            )
        }
        topics.send(COMMANDS, listOf("a", "b", "c").map { CommandMessages.command(wire(it), partition) })
        entries.partitionOf(ref).assert().isEqualTo(partition)
        entries.checkpointOf(SubscriptionStart.End, partition).assert().isEqualTo(3L)
        entries.checkpointOf(SubscriptionStart.Beginning, partition).assert().isEqualTo(0L)
        val resumed = entries.resume(ref, 1, 1).toList()
        resumed.map { (it.message as CommandWire).id }.assert().containsExactly("b", "c")
        resumed.map { entries.ordinalOf(it) }.assert().containsExactly(2L, 3L)
        resumed.map { entries.sourcePosition(it) }.assert().containsExactly(1L, 2L)
        resumed.first().stream.version.assert().isEqualTo(1)
        entries.ordinalOf(ParkedAggregate(CommandIntake.NAME, ref, 1, "r", 0, 1)).assert().isEqualTo(2L)
        entries.ordinalOf(ParkedAggregate(CommandIntake.NAME, ref, 1, "r", 0)).assert().isEqualTo(0L)
        entries.positionOf(ref, 1).assert().isNull()
        assertThrows<UnrecoverableException> { runBlocking { entries.resume(ref, 1, null) } }
        topics.retain(COMMANDS, partition, 2)
        assertThrows<UnrecoverableException> { runBlocking { entries.resume(ref, 1, 1) } }
            .message.assert().contains("no longer retains offset 1")
        assertThrows<IllegalArgumentException> { entries.requirePartition(PARTITIONS) }
    }

    @Test
    fun `a resume should not resolve an aggregate whose parked message moved to another partition`(): Unit =
        runBlocking {
            // Review R2.1-4: a topic that gained partitions maps the key elsewhere; the re-scan finds nothing there.
            val entries = MessageEntries.commands(COMMANDS, topics, PARTITIONS)
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            val before = (partition + 1) % PARTITIONS
            val other = generateSequence(0) { it + 1 }.map { ITEM.ref(TENANT, "i-$it") }
                .first { Partitioner.partitionOf(it, PARTITIONS) == partition && it != ref }
            val wire = { id: String, of: me.ahoo.wow.api.aggregate.AggregateRef ->
                ClusterCodec.wire("stock.item.Reserve", Command(id, id, of, Reserve(1)))
            }
            // Parked at offset 0 of the partition its key mapped to before.
            topics.send(COMMANDS, CommandMessages.command(wire("parked", ref), before))
            // Nothing at that offset in the partition it maps to now.
            assertThrows<UnrecoverableException> { runBlocking { entries.resume(ref, 1, 0).toList() } }
                .message.assert().contains("partitions changed")
            // Another aggregate's message at that offset there.
            topics.send(COMMANDS, CommandMessages.command(wire("other", other), partition))
            assertThrows<UnrecoverableException> { runBlocking { entries.resume(ref, 1, 0).toList() } }
                .message.assert().contains("partitions changed")
            // Where the message is, the re-scan goes on.
            topics.send(COMMANDS, CommandMessages.command(wire("own", ref), partition))
            entries.resume(ref, 1, 1).toList().map { (it.message as CommandWire).id }.assert().containsExactly("own")
        }

    @Test
    fun `a receipt should reach the handler once per command id with the command's class`(): Unit = runBlocking {
        val handled = CopyOnWriteArrayList<Pair<String, Class<*>>>()
        var answer: List<String>? = listOf("compensated")
        val receipts = Receipts(
            SALES,
            ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
            receiptHandler(rejected = { rejected, command ->
                handled += rejected.commandId to command
                answer
            }),
        )
        receipts.subscription.name.assert().isEqualTo("wow.receipts.sales")
        val rejected = CommandRejected<Any>(
            "c-1",
            "stock.item.Reserve",
            ref,
            ErrorInfo(INSUFFICIENT, "none"),
            Causation("root", "o-1@3"),
            execution,
        )
        receipts.handle(rejected)
        receipts.handle(rejected)
        handled.assert().containsExactly("c-1" to Reserve::class.java)
        // A ledger command no Manifest declares, refused back to the ops saga that sent it (decision D226).
        receipts.handle(rejected.copyWith(commandId = "c-ledger", commandType = Ledger.RESUME))
        handled.last().assert().isEqualTo("c-ledger" to ResumeParked::class.java)
        handled.removeLast()
        // No handler takes it: logged and passed over, once.
        answer = null
        receipts.handle(rejected.copyWith(commandId = "c-2"))
        receipts.handle(rejected.copyWith(commandId = "c-2"))
        handled.size.assert().isEqualTo(2)
        assertThrows<IllegalStateException> {
            runBlocking { receipts.handle(rejected.copyWith(commandId = "c-3", commandType = "stock.item.Gone")) }
        }
            .message.assert().contains("stock.item.Gone")
    }

    @Test
    fun `a commit after a rejection of the same command should reach the handler, a copy of either not`(): Unit =
        runBlocking {
            // Review R2.1-7: a rejection is not in the log (D47), so a command sent again may commit after it.
            val handled = CopyOnWriteArrayList<String>()
            val receipts = Receipts(
                SALES,
                ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
                receiptHandler(
                    rejected = { rejected, _ ->
                        handled += "rejected:${rejected.commandId}"
                        listOf("compensated")
                    },
                    committed = { committed, _ ->
                        handled += "committed:${committed.commandId}"
                        listOf("went on")
                    },
                ),
            )
            val rejected = CommandRejected<Any>(
                "c-1",
                "stock.item.Reserve",
                ref,
                ErrorInfo(INSUFFICIENT, "none"),
                Causation("root", "o-1@3"),
                execution,
            )
            receipts.handle(rejected)
            receipts.handle(committed("c-1"))
            receipts.handle(rejected)
            receipts.handle(committed("c-1", duplicate = true))
            handled.assert().containsExactly("rejected:c-1", "committed:c-1")
        }

    @Test
    fun `combined factories should ask each for the handler of a receipt in turn`(): Unit = runBlocking {
        val first = object : SubscriptionFactory {
            override fun subscriptions(
                context: ContextNode
            ) = emptyList<me.ahoo.wow.runtime.subscription.Subscription>()

            override fun receipts(context: ContextNode) = receiptHandler(
                rejected = { rejected, _ -> listOf("first").takeIf { rejected.commandId == "mine" } },
                committed = { committed, _ -> listOf("first").takeIf { committed.commandId == "mine" } },
            )
        }
        val second = object : SubscriptionFactory {
            override fun subscriptions(
                context: ContextNode
            ) = emptyList<me.ahoo.wow.runtime.subscription.Subscription>()

            override fun receipts(context: ContextNode) =
                receiptHandler(rejected = { _, _ -> listOf("second") }, committed = { _, _ -> listOf("second") })
        }
        val context = io.mockk.mockk<ContextNode>()
        val rejected = CommandRejected<Any>("mine", "t", ref, ErrorInfo("e", "m"), Causation("a", "b"), execution)
        val committed = { id: String -> committed(id) }
        val combined = (first + second).receipts(context)!!
        combined.rejected(rejected, Reserve::class.java).assert().containsExactly("first")
        combined.rejected(
            rejected.copyWith(commandId = "theirs"),
            Reserve::class.java
        ).assert().containsExactly("second")
        combined.committed(committed("mine"), Reserve::class.java).assert().containsExactly("first")
        combined.committed(committed("theirs"), Reserve::class.java).assert().containsExactly("second")
        (SubscriptionFactory.NONE + second).receipts(context).assert().isNotNull()
        (first + SubscriptionFactory.NONE).receipts(context).assert().isNotNull()
        (SubscriptionFactory.NONE + SubscriptionFactory.NONE).receipts(context).assert().isNull()
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
    }
}
