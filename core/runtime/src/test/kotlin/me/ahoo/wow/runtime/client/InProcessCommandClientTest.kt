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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.client.WowProblemException
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
    private val gateway = CommandGateway(
        ModelRegistry(listOf(model)),
        mapOf(
            CONTEXT to WaitCoordinator(
                Executor(Kernel(InMemoryEventLog(partitions = 2))),
                registry,
                SubscriptionDirectory.NONE,
                LocalSignalSender(origin.node, registry),
                WaitOptions(origin, maxTimeout = 30.seconds),
            ),
        ),
    )
    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    private val admin = Caller("ops", Scope("admin"), subject = "admin", crossScope = true)
    private val client = InProcessCommandClient(gateway).of(alice)
    private val cart = AggregateType(CONTEXT, "cart")

    @Test
    fun `a command of the node's own context runs in process, the object itself, as the caller`(): Unit =
        runBlocking {
            val body = AddItem("apple")
            val result = client.wait(body, cart.ref(TENANT, "c-1"), SendOptions.of(wait = WaitPlan.COMMITTED))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            commands.single().body.assert().isSameAs(body)
            commands.single().operator!!.id.assert().isEqualTo("alice")
        }

    @Test
    fun `the options give the request id, the expected version, the timeout and the target scope`(): Unit =
        runBlocking {
            val created = client.wait(OpenCart(), cart.unassigned(TENANT), SendOptions.of(requestId = "r-1"))
            created.requestId.assert().isEqualTo("r-1")
            created.ref.isAssigned.assert().isTrue()
            val mismatch = assertThrows<CommandRejectedException> {
                runBlocking { client.wait(AddItem("pear"), created.ref, SendOptions.of(expectedVersion = 7)) }
            }
            mismatch.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_VERSION_MISMATCH)
            val targeted = InProcessCommandClient(gateway).of(admin).wait(
                AddItem("kiwi"),
                cart.ref(TENANT, "c-2"),
                SendOptions.of(
                    wait = WaitPlan.COMMITTED,
                    timeout = 5.seconds,
                    targetTenant = TENANT,
                    targetScope = Scope("alice")
                ),
            )
            targeted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            commands.last().scope.assert().isEqualTo(Scope("alice"))
            commands.last().wait.timeout.assert().isEqualTo(5.seconds)
            // A target tenant alone acts in the target tenant, in no scope.
            InProcessCommandClient(gateway).of(admin)
                .wait(
                    AddItem("fig"),
                    cart.ref(TENANT, "c-6"),
                    SendOptions.of(wait = WaitPlan.COMMITTED, targetTenant = TENANT)
                )
            commands.last().scope.assert().isEqualTo(Scope.NONE)
            client.send(AddItem("lime"), cart.ref(TENANT, "c-7"), SendOptions.of(timeout = 3.seconds))
                .hop.stage.assert().isEqualTo(Stage.ACCEPTED)
            SendOptions.of(targetTenant = TENANT).targeted.assert().isTrue()
            SendOptions.of(timeout = 3.seconds).plan.assert().isEqualTo(WaitPlan.DEFAULT.within(3.seconds))
            SendOptions.DEFAULT.plan.assert().isNull()
        }

    @Test
    fun `send answers at ACCEPTED, and stream emits each result once collected`(): Unit = runBlocking {
        val accepted = client.send(AddItem("apple"), cart.ref(TENANT, "c-3"), SendOptions.of(wait = WaitPlan.COMMITTED))
        accepted.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        val stream = client.stream(AddItem("pear"), cart.ref(TENANT, "c-5"), SendOptions.of(wait = WaitPlan.COMMITTED))
        // Nothing is sent before the flow is collected.
        commands.none { it.ref.id == "c-5" }.assert().isTrue()
        stream.toList().single().hop.assert().isEqualTo(Hop.committed(CONTEXT))
        // A rejection is a result like any other in a stream.
        val rejected = client.stream(
            AddItem("many", 11),
            cart.ref(TENANT, "c-3"),
            SendOptions.of(wait = WaitPlan.COMMITTED)
        )
            .toList()
        rejected.single().status.assert().isEqualTo(CommandResult.Status.REJECTED)
    }

    @Test
    fun `a rejection throws its code, at admission as by the aggregate`(): Unit = runBlocking {
        val domain = assertThrows<CommandRejectedException> {
            runBlocking {
                client.wait(
                    AddItem("many", 11),
                    cart.ref(TENANT, "c-4"),
                    SendOptions.of(wait = WaitPlan.COMMITTED)
                )
            }
        }
        domain.code.assert().isEqualTo("shop.cart.too-many")
        domain.message.assert().contains("shop.cart.too-many")
        val unhandled = assertThrows<CommandRejectedException> {
            runBlocking { client.wait(AddItem("x"), AggregateType("elsewhere", "cart").ref(TENANT, "c")) }
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
            val committed = peerClient.wait(Reserve(1), ITEM.ref("t", "i-1"), SendOptions.of(wait = WaitPlan.COMMITTED))
            committed.hop.assert().isEqualTo(Hop.committed(STOCK))
            committed.version.assert().isEqualTo(1)
            peerClient.send(Reserve(1), ITEM.ref("t", "i-2")).hop.assert().isEqualTo(Hop.accepted(STOCK))
            assertThrows<CommandRejectedException> {
                runBlocking {
                    peerClient.wait(
                        Reserve(1),
                        ITEM.ref("t", "i-3"),
                        SendOptions.of(wait = WaitPlan.SNAPSHOT)
                    )
                }
            }.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        } finally {
            nodes.forEach { it.stop() }
        }
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
