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
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The id of an aggregate whose id is its owner's (`@AggregateRoot(idSource = OWNER)`) on the peer path (issue #6558):
 * the sales node relays a command of the stock context's basket, known from its contract, and decides its id as the
 * stock node's own gateway does; a command the stock intake takes that names no basket and has no owner is refused.
 */
class PeerOwnerIdTest {
    data class OpenBasket(val note: String = "")
    data class PutItem(val sku: String)
    data class BasketOpened(val note: String)
    data class ItemPut(val sku: String)

    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stockBackends = InMemoryBackends(PARTITIONS)
    private val signals = SignalSender { origin: Origin, signal: WaitSignal, _ ->
        nodes.firstOrNull { it.node == origin.node }?.waits?.signal(signal)
    }

    /** The commands the basket decided. */
    private val decided = CopyOnWriteArrayList<Command<*>>()

    private val basket: AggregateModel<Any> = aggregate(STOCK, "basket") {
        id(AggregateIdSource.OWNER)
        initial { Any() }
        decide<OpenBasket>(Creation.REQUIRED) { _, body ->
            decided += command
            Decision.events(BasketOpened(body.note))
        }
        decide<PutItem> { _, body ->
            decided += command
            Decision.events(ItemPut(body.sku))
        }
        evolve<BasketOpened> { }
        evolve<ItemPut> { }
    }

    private val manifest: Manifest = stockManifest().let { stock ->
        val context = stock.contexts.single()
        stock.copyWith(contexts = listOf(context.copyWith(aggregates = context.aggregates + basketManifest())))
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private suspend fun started(): Pair<ServiceNode, ServiceNode> {
        val stock = ServiceNode(
            STOCK_SERVICE,
            "stock-1",
            "stock-1.internal:9090",
            listOf(StockItems().model, basket),
            manifest,
            stockBackends,
            channel,
            remoteSignals = signals,
            options = fastNodeOptions(),
            contracts = ContractDirectory(manifest),
            topics = SourceTopics(topics, PREFIX, topics, intake = true),
        )
        val sales = ServiceNode(
            SALES_SERVICE,
            "sales-1",
            "sales-1.internal:9090",
            listOf(orderModel()),
            salesManifest(),
            InMemoryBackends(PARTITIONS),
            channel,
            remoteSignals = signals,
            options = fastNodeOptions(),
            contracts = ContractDirectory(salesManifest(), listOf(peer(manifest))),
            topics = SourceTopics(topics, PREFIX, topics),
        )
        listOf(stock, sales).forEach {
            nodes += it
            channel.bind(it.address, it.forwardHandler)
            it.start()
        }
        return stock to sales
    }

    private fun request(command: Any, caller: Caller, id: String? = null) = ServerCommandRequest.builder(
        command,
        if (id == null) BASKET.unassigned(TENANT) else BASKET.ref(TENANT, id),
    ).caller(caller).wait(WaitPlan.COMMITTED.within(10.seconds)).build()

    @Test
    fun `an owner's command naming no basket is relayed to its own basket`(): Unit = runBlocking {
        val (_, sales) = started()
        val opened = sales.gateway.execute(request(OpenBasket(), alice))
        listOf(opened.status, opened.ref).assert()
            .isEqualTo(listOf(CommandResult.Status.SUCCEEDED, BASKET.ref(TENANT, "alice")))
        val put = sales.gateway.execute(request(PutItem("apple"), alice))
        put.status.assert().withFailMessage { "$put" }.isEqualTo(CommandResult.Status.SUCCEEDED)
        put.ref.assert().isEqualTo(BASKET.ref(TENANT, "alice"))
        // Relayed with its id decided: the stock intake takes the owner's basket, as the sales node named it.
        topics.messages(COMMANDS).map { CommandMessages.commandOf(EnvelopeCodec.decode(it.value)).ref }.assert()
            .containsExactly(BASKET.ref(TENANT, "alice"), BASKET.ref(TENANT, "alice"))
        decided.map { it.ref.id }.assert().containsExactly("alice", "alice")
    }

    @Test
    fun `a caller with no owner naming no basket is refused before anything is relayed`(): Unit = runBlocking {
        val (_, sales) = started()
        val refused = sales.gateway.execute(request(OpenBasket(), clerk))
        refused.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        refused.error!!.message.assert().isEqualTo("This caller has no owner; address the aggregate by its id.")
        refused.ref.assert().isEqualTo(BASKET.ref(TENANT, CommandGateway.UNADDRESSED))
        sales.gateway.execute(request(OpenBasket(), anonymous)).error!!.code.assert()
            .isEqualTo(FrameworkCodes.AUTH_UNAUTHENTICATED)
        topics.messages(COMMANDS).assert().isEmpty()
        // Naming the basket, it is relayed.
        sales.gateway.execute(request(OpenBasket(), clerk, id = "carol")).ref.assert()
            .isEqualTo(BASKET.ref(TENANT, "carol"))
    }

    @Test
    fun `the same requests get the same answers from the basket's own node and through a peer`(): Unit =
        runBlocking {
            val (stock, sales) = started()
            val refusals = listOf(
                request(OpenBasket(), clerk),
                request(PutItem("apple"), clerk),
                request(OpenBasket(), anonymous),
                request(PutItem("apple"), anonymous),
            )
            refusals.forEach { request ->
                val local = stock.gateway.execute(request)
                val relayed = sales.gateway.execute(request)
                local.status.assert().isEqualTo(CommandResult.Status.REJECTED)
                listOf(relayed.status, relayed.error!!.code, relayed.error!!.message, relayed.ref).assert()
                    .isEqualTo(listOf(local.status, local.error!!.code, local.error!!.message, local.ref))
            }
            // Each owner's own basket, created and then addressed without an id, on either path.
            listOf(stock to alice, sales to bob).forEach { (node, owner) ->
                listOf(OpenBasket(), PutItem("apple")).forEach { command ->
                    val result = node.gateway.execute(request(command, owner))
                    listOf(result.status, result.ref).assert()
                        .isEqualTo(listOf(CommandResult.Status.SUCCEEDED, BASKET.ref(TENANT, owner.subject!!)))
                }
            }
        }

    @Test
    fun `a command the intake takes that names no basket and has no owner is rejected, not parked`(): Unit =
        runBlocking {
            val (stock, _) = started()
            val unowned = Command("c-n", "r-c-n", BASKET.unassigned(TENANT), PutItem("apple"))
            val replyTo = ReplyTo(SALES, "OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-n"), 1)
            val lane = BASKET.ref(TENANT, "(none):r-c-n")
            topics.send(
                COMMANDS,
                CommandMessages.command(
                    ClusterCodec.wire("stock.basket.PutItem", unowned, replyTo),
                    Partitioner.partitionOf(lane, PARTITIONS),
                ),
            )
            val ledger = stockBackends.backends(STOCK).ledger
            eventually { topics.messages(RECEIPTS).isNotEmpty() }
            val rejected = CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
            rejected.commandId.assert().isEqualTo("c-n")
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            rejected.error.message.assert().isEqualTo("This caller has no owner; address the aggregate by its id.")
            ledger.isParked(INTAKE, lane).assert().isFalse()
            decided.assert().isEmpty()
            stock.gateway.execute(request(OpenBasket(), alice)).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }

    private fun basketManifest() = AggregateManifest(
        "basket",
        state = StateManifest(Manifest.schemaRef("basketState")),
        commands = listOf(
            CommandManifest(
                OpenBasket::class.java.simpleName,
                schema = Manifest.schemaRef(OpenBasket::class.java.name),
                creation = Creation.REQUIRED,
            ),
            CommandManifest(PutItem::class.java.simpleName, schema = Manifest.schemaRef(PutItem::class.java.name)),
        ) + (Lifecycle.COMMANDS.keys - Lifecycle.TRANSFER_OWNER).map {
            SystemCommands.lifecycle(STOCK, "basket", it, owned = true)
        },
        events = listOf(BasketOpened::class.java, ItemPut::class.java)
            .map { EventManifest(it.simpleName, schema = Manifest.schemaRef(it.name)) },
        idSource = AggregateIdSource.OWNER,
    )

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
        val BASKET = AggregateType(STOCK, "basket")
        val INTAKE = SubscriptionId(STOCK, CommandIntake.NAME)
        val alice = Caller(TENANT, Scope("alice"), subject = "alice")
        val bob = Caller(TENANT, Scope("bob"), subject = "bob")
        val clerk = Caller(TENANT, subject = "clerk")
        val anonymous = Caller(TENANT)
    }
}
