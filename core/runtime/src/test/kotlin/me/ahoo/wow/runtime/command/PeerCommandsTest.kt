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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Commands of a peer context from code that is not a saga (decision D259, review M-1), between a sales service and a
 * stock service over [InMemoryTopics]: the sales node's gateway admits a stock reservation it does not serve, known from
 * the stock's contract, and sends it to the stock's command topic, the path a saga's command takes; the stock intake
 * executes it and signals the waiting sales node.
 */
class PeerCommandsTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems().apply { broken = false }
    private val actions = CopyOnWriteArrayList<CommandAction>()

    private val signals = SignalSender { origin: Origin, signal: WaitSignal, _ ->
        nodes.firstOrNull { it.node == origin.node }?.waits?.signal(signal)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun stockNode(): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(stock.model),
        stockManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        remoteSignals = signals,
        options = fastNodeOptions(),
        topics = SourceTopics(topics, PREFIX, topics, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun salesNode(transport: Boolean = true): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel()),
        salesManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        remoteSignals = signals,
        options = fastNodeOptions(),
        admission = AdmissionPlugins(
            authorizer = CommandAuthorizer { caller, action ->
                actions += action
                if (caller.subject == "mallory") Authorization.Denied("No.") else Authorization.Allowed
            },
            validators = listOf(
                CommandValidator { _, body ->
                    if ((body as? Reserve)?.quantity == 0) {
                        listOf(
                            Violation("quantity", "Min", "At least 1.")
                        )
                    } else {
                        emptyList()
                    }
                },
            ),
        ),
        contracts = ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        topics = if (transport) SourceTopics(topics, PREFIX, topics) else null,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun started(): ServiceNode {
        stockNode().start()
        return salesNode().also { it.start() }
    }

    private val clerk = Caller(TENANT, Scope("clerk"), subject = "clerk")

    private fun reserve(
        quantity: Int,
        item: String? = "i-1",
        wait: WaitPlan? = WaitPlan.COMMITTED.within(10.seconds),
        caller: Caller = clerk,
    ) = ServerCommandRequest.builder(Reserve(quantity), if (item == null) ITEM.unassigned(TENANT) else ITEM.ref(TENANT, item))
        .caller(caller)
        .wait(wait)
        .build()

    @Test
    fun `a peer's command should go through its command topic and answer at ACCEPTED once durable`(): Unit =
        runBlocking {
            val sales = started()
            val accepted = sales.gateway.execute(reserve(2, wait = WaitPlan.ACCEPTED))
            accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            accepted.hop.assert().isEqualTo(Hop.accepted(STOCK))
            accepted.function.name.assert().isEqualTo("Reserve")
            val envelope = EnvelopeCodec.decode(topics.messages(COMMANDS).single().value)
            val wire = CommandMessages.commandOf(envelope)
            wire.type.assert().isEqualTo("stock.item.Reserve")
            wire.id.assert().isEqualTo(accepted.commandId)
            wire.ref.assert().isEqualTo(ITEM.ref(TENANT, "i-1"))
            wire.scope.assert().isEqualTo(Scope("clerk"))
            wire.operator!!.id.assert().isEqualTo("clerk")
            envelope.transport.assert().isNull()
            eventually { accepted.commandId in stock.decided }
            // Checked as a command of the node is: authorized as the contract's command type.
            actions.single().type.name.assert().isEqualTo("stock.item.Reserve")
        }

    @Test
    fun `a wait for the peer's COMMITTED should end with the result its intake signals`(): Unit = runBlocking {
        val sales = started()
        val results = sales.gateway.send(reserve(3)).toList()
        val committed = results.single()
        committed.hop.assert().isEqualTo(Hop.committed(STOCK))
        committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        committed.version.assert().isEqualTo(1)
        val wait = EnvelopeCodec.decode(topics.messages(COMMANDS).single().value).transport!!.wait!!
        wait.remaining.assert().containsExactly(Hop.committed(STOCK))
        wait.origin.node.assert().isEqualTo("sales-1")
        // A rejection is signalled as well, at once.
        val rejected = sales.gateway.execute(reserve(AVAILABLE + 1, item = "i-2"))
        rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejected.error!!.code.assert().isEqualTo(INSUFFICIENT)
        // With no plan, the default: the peer's COMMITTED.
        val defaulted = sales.gateway.execute(reserve(1, item = "i-3", wait = null))
        defaulted.hop.assert().isEqualTo(Hop.committed(STOCK))
        defaulted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // A JSON body names the command, as an edge's request does: the same path.
        val json = ServerCommandRequest.builder(STOCK, "item", "Reserve", CommandBody.of("""{"quantity":1}"""))
            .caller(clerk)
            .aggregateId("i-4")
            .wait(WaitPlan.COMMITTED)
            .build()
        sales.gateway.execute(json).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a creation without an id should take the one the peer's intake gives it`(): Unit = runBlocking {
        val sales = started()
        val created = sales.gateway.execute(reserve(1, item = null))
        created.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        created.ref.isAssigned.assert().isTrue()
        val accepted = sales.gateway.execute(reserve(1, item = null, wait = WaitPlan.ACCEPTED))
        accepted.ref.isAssigned.assert().isFalse()
    }

    @Test
    fun `a wait beyond the peer's COMMITTED should be refused at admission`(): Unit = runBlocking {
        val sales = started()
        listOf(
            WaitPlan.SNAPSHOT,
            WaitPlan.of(Hop.committed(), Hop.handled("Board.onOrder", STOCK)),
            WaitPlan.of(Hop.committed(SALES)),
        ).forEach { plan ->
            val result = sales.gateway.execute(reserve(1, wait = plan))
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
            result.error!!.message.assert().contains("command topic")
        }
        topics.messages(COMMANDS).assert().isEmpty()
    }

    @Test
    fun `a peer's command is refused as a command of the node is`(): Unit = runBlocking {
        val sales = started()
        sales.gateway.execute(reserve(0)).error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        sales.gateway.execute(reserve(1, caller = Caller(TENANT, Scope("m"), subject = "mallory")))
            .error!!.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        sales.gateway.execute(
            ServerCommandRequest.builder(
                Reserve(1),
                ITEM.ref(TENANT, "i")
            ).caller(clerk).target(TargetScope(TENANT, Scope("x")))
                .build(),
        ).error!!.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        sales.gateway.execute(ServerCommandRequest.builder(Reserve(1), ITEM.ref("other", "i")).caller(clerk).build())
            .error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        sales.gateway.execute(
            ServerCommandRequest.builder(STOCK, "item", "Reserve", CommandBody.of("""{"quantity":"many"}"""))
                .caller(clerk).aggregateId("i").build(),
        ).error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        val call = CallContext.of("c-1", TENANT, Scope.NONE, me.ahoo.wow.api.decide.Causation("r", "r"))
        sales.gateway.execute(
            ServerCommandRequest(STOCK, "item", "Reserve", CommandBody.Value(Reserve(1)), clerk, "i", call = call),
        ).error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        topics.messages(COMMANDS).assert().isEmpty()
    }

    @Test
    fun `an unknown command or context stays unhandled`(): Unit = runBlocking {
        val sales = started()
        val unknownCommand = sales.gateway.execute(
            ServerCommandRequest.builder(
                STOCK,
                "item",
                "Release",
                CommandBody.of("{}")
            ).caller(clerk).aggregateId("i").build(),
        )
        unknownCommand.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        unknownCommand.error!!.message.assert().contains("has no command Release")
        val unknownContext = sales.gateway.execute(
            ServerCommandRequest.builder(
                Reserve(1),
                AggregateType("billing", "invoice").ref(TENANT, "x")
            ).caller(clerk).build(),
        )
        unknownContext.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        unknownContext.error!!.message.assert().contains("No aggregate billing.invoice is served here")
        // A command class of another aggregate is not this one's.
        val misdirected = sales.gateway.execute(
            ServerCommandRequest.builder(PlaceOrder(1), ITEM.ref(TENANT, "x")).caller(clerk).build(),
        )
        misdirected.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
    }

    @Test
    fun `a node without a command transport should refuse a peer's command, saying why`(): Unit = runBlocking {
        val sales = salesNode(transport = false).also { it.start() }
        val result = sales.gateway.execute(reserve(1))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        result.error!!.message.assert().contains("no command transport").contains("wow.kafka.bootstrap-servers")
    }

    @Test
    fun `a broker that does not acknowledge should fail the command at ACCEPTED`(): Unit = runBlocking {
        val sales = started()
        topics.failure = IllegalStateException("The broker is down.")
        listOf(WaitPlan.ACCEPTED, WaitPlan.COMMITTED).forEach { plan ->
            val result = sales.gateway.execute(reserve(1, wait = plan))
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
            result.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
        }
        topics.failure = null
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
    }
}
