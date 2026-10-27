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

package me.ahoo.wow.runtime.client

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.client.send
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.PREFIX
import me.ahoo.wow.runtime.command.Reserve
import me.ahoo.wow.runtime.command.SALES_SERVICE
import me.ahoo.wow.runtime.command.STOCK
import me.ahoo.wow.runtime.command.STOCK_SERVICE
import me.ahoo.wow.runtime.command.StockItems
import me.ahoo.wow.runtime.command.orderModel
import me.ahoo.wow.runtime.command.peer
import me.ahoo.wow.runtime.command.salesManifest
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The in-process [me.ahoo.wow.api.client.CommandClient] (decisions D257, D258, D259): command objects through the
 * node's gateway, in process for its own aggregates, through a peer's command topic for a peer's.
 */
class InProcessCommandClientTest {
    data class OpenCart(val note: String = "")
    data class AddItem(val sku: String, val quantity: Int = 1)

    class Cart

    private val commands = CopyOnWriteArrayList<Command<*>>()

    private val model = aggregate<Cart>(CONTEXT, "cart") {
        initial { Cart() }
        decide<OpenCart>(Creation.REQUIRED) { _, body ->
            commands += command
            Decision.events(body)
        }
        decide<AddItem>(Creation.ALLOWED) { _, body ->
            commands += command
            if (body.quantity > 10) Decision.reject("shop.cart.too-many", "At most 10.") else Decision.events(body)
        }
        evolve<OpenCart> { }
        evolve<AddItem> { }
    }

    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")

    private fun gateway(
        models: List<me.ahoo.wow.kernel.AggregateModel<*>> = listOf(model),
        transformers: List<CommandTransformer> = emptyList(),
    ) = CommandGateway(
        ModelRegistry(models),
        mapOf(
            CONTEXT to WaitCoordinator(
                Executor(Kernel(InMemoryEventLog(partitions = 2))),
                registry,
                SubscriptionDirectory.NONE,
                LocalSignalSender(origin.node, registry),
                origin, WaitOptions(maxTimeout = 30.seconds),
            ),
        ),
        transformers = transformers,
    )

    private val gateway = gateway()
    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    private val admin = Caller("ops", Scope("admin"), subject = "admin", crossScope = true)
    private val client = InProcessCommandClient(gateway).of(alice)
    private val cart = AggregateType(CONTEXT, "cart")

    @Test
    fun `a command of the node's own context runs in process, the object itself, as the caller`(): Unit =
        runBlocking {
            val body = AddItem("apple")
            val result = client.wait(CommandRequest.of(body, "c-1", wait = WaitPlan.COMMITTED))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            result.ref.assert().isEqualTo(cart.ref(TENANT, "c-1"))
            commands.single().body.assert().isSameAs(body)
            commands.single().operator!!.id.assert().isEqualTo("alice")
        }

    @Test
    fun `a request's type is derived from its command's class, else it says to set it`(): Unit = runBlocking {
        // A creation names no id: the server assigns it.
        val created = client.wait(OpenCart())
        created.ref.type.assert().isEqualTo(cart)
        created.ref.isAssigned.assert().isTrue()
        assertThrows<IllegalArgumentException> { runBlocking { client.wait(Unknown) } }
            .message.assert().contains("decided by no aggregate")
        val wishlist = aggregate<Cart>(CONTEXT, "wishlist") {
            initial { Cart() }
            decide<AddItem>(Creation.ALLOWED) { _, body -> Decision.events(body) }
            evolve<AddItem> { }
        }
        val both = InProcessCommandClient(gateway(listOf(model, wishlist))).of(alice)
        assertThrows<IllegalArgumentException> { runBlocking { both.wait(AddItem("fig"), "c-1") } }
            .message.assert().contains("several aggregates")
        both.wait(CommandRequest.of(AddItem("fig"), "w-1", AggregateType(CONTEXT, "wishlist")))
            .ref.type.name.assert().isEqualTo("wishlist")
    }

    @Test
    fun `an unaddressed command names no id, and a transformer finds its aggregate`(): Unit = runBlocking {
        val mine = object : CommandTransformer {
            override val input = model.commandType(AddItem::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget =
                CommandTarget(input.type, input.aggregateId ?: "c-alice", input.body, input.headers)
        }
        val result = InProcessCommandClient(gateway(transformers = listOf(mine))).of(alice).wait(AddItem("pear"))
        result.ref.assert().isEqualTo(cart.ref(TENANT, "c-alice"))
    }

    @Test
    fun `the request gives the request id, the expected version, the timeout and the target scope`(): Unit =
        runBlocking {
            val created = client.wait(CommandRequest.of(OpenCart(), requestId = "r-1"))
            created.requestId.assert().isEqualTo("r-1")
            val mismatch = assertThrows<CommandRejectedException> {
                runBlocking { client.wait(CommandRequest.of(AddItem("pear"), created.ref.id, expectedVersion = 7)) }
            }
            mismatch.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_VERSION_MISMATCH)
            val targeted = InProcessCommandClient(gateway).of(admin).wait(
                CommandRequest.builder(cart.ref(TENANT, "c-2"), AddItem("kiwi"))
                    .wait(WaitPlan.COMMITTED).timeout(5.seconds).targetScope(Scope("alice")).build(),
            )
            targeted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            commands.last().scope.assert().isEqualTo(Scope("alice"))
            commands.last().wait.timeout.assert().isEqualTo(5.seconds)
            // A target tenant alone acts in the target tenant, in no scope.
            InProcessCommandClient(gateway).of(admin)
                .wait(CommandRequest.builder(cart.ref(TENANT, "c-6"), AddItem("fig")).wait(WaitPlan.COMMITTED).build())
            commands.last().scope.assert().isEqualTo(Scope.NONE)
            commands.last().ref.tenantId.assert().isEqualTo(TENANT)
            client.send(CommandRequest.of(AddItem("lime"), "c-7", timeout = 3.seconds))
                .hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        }

    @Test
    fun `a request to a ref of the caller's own tenant is no cross-scope request`(): Unit = runBlocking {
        // A saga answers the event's aggregate: its ref names the caller's own tenant, which targets nothing.
        client.wait(CommandRequest.builder(cart.ref(TENANT, "c-9"), AddItem("plum")).wait(WaitPlan.COMMITTED).build())
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // Another tenant is a target, which only a cross-scope caller may name.
        assertThrows<CommandRejectedException> {
            runBlocking { client.wait(CommandRequest.to(cart.ref("other", "c-9"), AddItem("plum"))) }
        }
    }

    @Test
    fun `a command sent while deciding takes a request id derived from the deciding command`(): Unit = runBlocking {
        withContext(DecidingCommand("deciding")) {
            client.wait(CommandRequest.of(AddItem("plum"), "c-8", wait = WaitPlan.COMMITTED))
            commands.last().requestId.assert().isEqualTo(DecidingCommand.requestIdOf("deciding", 0))
            client.stream(CommandRequest.of(AddItem("plum"), "c-8", wait = WaitPlan.COMMITTED)).toList()
            commands.last().requestId.assert().isEqualTo(DecidingCommand.requestIdOf("deciding", 1))
            client.wait(CommandRequest.of(AddItem("plum"), "c-8", requestId = "own"))
            commands.last().requestId.assert().isEqualTo("own")
        }
    }

    @Test
    fun `send answers at ACCEPTED, and stream emits each result once collected`(): Unit = runBlocking {
        val accepted = client.send(CommandRequest.of(AddItem("apple"), "c-3", wait = WaitPlan.COMMITTED))
        accepted.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        client.send(AddItem("apple"), "c-3").hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        val stream = client.stream(CommandRequest.of(AddItem("pear"), "c-5", wait = WaitPlan.COMMITTED))
        // Nothing is sent before the flow is collected.
        commands.none { it.ref.id == "c-5" }.assert().isTrue()
        stream.toList().single().hop.assert().isEqualTo(Hop.committed(CONTEXT))
        // A rejection is a result like any other in a stream.
        val rejected = client.stream(CommandRequest.of(AddItem("many", 11), "c-3", wait = WaitPlan.COMMITTED)).toList()
        rejected.single().status.assert().isEqualTo(CommandResult.Status.REJECTED)
    }

    @Test
    fun `a rejection throws its code, at admission as by the aggregate`(): Unit = runBlocking {
        val domain = assertThrows<CommandRejectedException> {
            runBlocking { client.wait(CommandRequest.of(AddItem("many", 11), "c-4", wait = WaitPlan.COMMITTED)) }
        }
        domain.code.assert().isEqualTo("shop.cart.too-many")
        domain.message.assert().contains("shop.cart.too-many")
        val unhandled = assertThrows<CommandRejectedException> {
            runBlocking { client.wait(CommandRequest.to(AggregateType("elsewhere", "cart").ref(TENANT, "c"), AddItem("x"))) }
        }
        unhandled.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
    }

    @Test
    fun `a failure throws a problem with its status and retry hint`() {
        fun failed(code: String) = CommandResult(
            commandId = "c",
            requestId = "r",
            correlationId = "c",
            ref = cart.ref(TENANT, "c"),
            hop = Hop.accepted(CONTEXT),
            function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "AddItem"),
            status = CommandResult.Status.FAILED,
            error = ErrorInfo(code, "It failed."),
            signalTime = 0,
        )
        val busy = assertThrows<WowProblemException> { InProcessCommandClient.settled(failed(ResultCodes.BUSY)) }
        busy.code.assert().isEqualTo(ResultCodes.BUSY)
        busy.status.assert().isEqualTo(429)
        busy.retryHint.assert().isEqualTo(RetryHint(retryable = true, sameRequestId = true, retryAfter = 1.seconds))
        busy.result!!.error!!.message.assert().isEqualTo("It failed.")
        val timedOut = InProcessCommandClient.problemOf(failed(ResultCodes.WAIT_TIMEOUT))
        timedOut.status.assert().isEqualTo(504)
        timedOut.retryHint.assert().isEqualTo(RetryHint(retryable = true, sameRequestId = true))
        val crashed = InProcessCommandClient.problemOf(failed(ResultCodes.FAILED))
        crashed.status.assert().isEqualTo(500)
        crashed.retryHint.assert().isEqualTo(RetryHint.NONE)
        InProcessCommandClient.problemOf(failed("wow.something.new")).status.assert().isEqualTo(500)
        val down = InProcessCommandClient.problemOf(failed(ResultCodes.STORAGE_UNAVAILABLE))
        down.status.assert().isEqualTo(503)
        down.retryHint.retryAfter.assert().isEqualTo(1.seconds)
        down.message.assert().contains(ResultCodes.STORAGE_UNAVAILABLE).endsWith("It failed.")
        assertThrows<IllegalArgumentException> { CommandRejectedException(failed(ResultCodes.BUSY)) }
    }

    @Test
    fun `a peer's command goes through its command topic`(): Unit = runBlocking {
        val topics = InMemoryTopics(2).also { it.create(PREFIX + "wow.stock.commands", 4) }
        val channel = LoopbackClusterChannel()
        val nodes = CopyOnWriteArrayList<ServiceNode>()
        val signals = SignalSender { to: Origin, signal: WaitSignal, _ ->
            nodes.firstOrNull { it.node == to.node }?.waits?.signal(signal)
        }
        val stock = StockItems().apply { broken = false }
        fun node(
            service: String,
            models: List<me.ahoo.wow.kernel.AggregateModel<*>>,
            contracts: ContractDirectory,
            intake: Boolean
        ) =
            ServiceNode(
                service,
                "$service-1",
                "$service-1.internal:9090",
                models,
                contracts.local,
                InMemoryBackends(4),
                channel,
                remoteSignals = signals,
                options = fastNodeOptions(),
                contracts = contracts,
                topics = SourceTopics(topics, PREFIX, topics, intake = intake),
            ).also {
                nodes += it
                channel.bind(it.address, it.forwardHandler)
            }
        try {
            node(STOCK_SERVICE, listOf(stock.model), ContractDirectory(stockManifest()), intake = true).start()
            val sales = node(
                SALES_SERVICE,
                listOf(orderModel()),
                ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
                intake = false,
            ).also { it.start() }
            val peerClient = InProcessCommandClient(sales.gateway).of(Caller("t", subject = "clerk"))
            // The peer's type is derived from its contract.
            val committed = peerClient.wait(CommandRequest.of(Reserve(1), "i-1", wait = WaitPlan.COMMITTED))
            committed.hop.assert().isEqualTo(Hop.committed(STOCK))
            committed.ref.assert().isEqualTo(ITEM.ref("t", "i-1"))
            committed.version.assert().isEqualTo(1)
            peerClient.send(CommandRequest.to(ITEM.ref("t", "i-2"), Reserve(1))).hop.assert().isEqualTo(Hop.accepted(STOCK))
            assertThrows<CommandRejectedException> {
                runBlocking { peerClient.wait(CommandRequest.of(Reserve(1), "i-3", wait = WaitPlan.SNAPSHOT)) }
            }.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        } finally {
            nodes.forEach { it.stop() }
        }
    }

    private data object Unknown

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
