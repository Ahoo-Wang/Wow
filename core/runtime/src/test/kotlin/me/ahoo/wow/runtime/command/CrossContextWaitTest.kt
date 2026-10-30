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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import me.ahoo.wow.manifest.FunctionKind as ManifestFunctionKind

/**
 * Waits across contexts (design 04 §6.3; decisions D34, D62, D65, D137, D138), between a sales service and a stock
 * service over [InMemoryTopics], each node signalling the other's waits as `/wow/internal/signals` would:
 *
 * - the sales saga's commands carry the rest of the chain on through the stock context's command topic, and the stock
 *   intake signals their COMMITTED, or their rejection at once, to the node the wait started on;
 * - a subscription of the stock context that reads the sales context's published stream signals its own hop, of the
 *   stock context, and a saga of it carries the chain on into what it derives;
 * - a chain is checked against both Manifests, the saga's `derives` included;
 * - a signal the origin never gets costs the wait its deadline, never more.
 */
class CrossContextWaitTest {
    private val topics = InMemoryTopics(2).also {
        it.create(COMMANDS, PARTITIONS)
        it.create(ORDERS, 2)
    }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()
    private val boarded = CopyOnWriteArrayList<StateEvent<OrderView>>()
    private val signalled = CopyOnWriteArrayList<WaitSignal>()

    /** Whether the sales node is gone: the signals to it are lost, as a request to a killed node fails. */
    @Volatile
    private var salesGone = false

    /** The stock context's view of a published order. */
    data class OrderView(val quantity: Int, val cancelled: Boolean)

    /** Every node's signals to another node go straight to that node's waits, unless it is gone. */
    private val signals = SignalSender { origin: Origin, signal: WaitSignal, _ ->
        signalled += signal
        if (!(salesGone && origin.service == SALES_SERVICE)) {
            nodes.firstOrNull { it.node == origin.node }?.waits?.signal(signal)
        }
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun stockSubscription(name: String, kind: SubscriptionKind, derives: List<String>? = null) =
        SubscriptionManifest(
            name = name,
            kind = kind,
            source = SubscriptionSource(SALES, listOf("order")),
            function = FunctionManifest(
                if (kind == SubscriptionKind.SAGA) ManifestFunctionKind.SAGA else ManifestFunctionKind.PROJECT,
                STOCK,
                name.substringBefore('.'),
                name.substringAfter('.'),
            ),
            derives = derives,
        )

    /** The sales Manifest: it publishes its orders, and its saga declares it derives the stock's reservations. */
    private val salesManifest: Manifest = salesManifest().let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map {
                it.copyWith(
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = SAGA,
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(SALES, listOf("order")),
                            function = FunctionManifest(ManifestFunctionKind.SAGA, SALES, "OrderSaga", "onOrderPlaced"),
                            derives = listOf(Manifest.schemaRef(Reserve::class.java.name)),
                        ),
                    ),
                    published = listOf(PublishedManifest("order", PublishedManifest.defaultTopic(SALES, "order"))),
                )
            },
        )
    }

    /** The stock Manifest: a projection and a saga of the published orders. */
    private val stockManifest: Manifest = stockManifest().let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map {
                it.copyWith(
                    subscriptions = listOf(
                        stockSubscription(BOARD, SubscriptionKind.PROJECTION),
                        stockSubscription(PICKER, SubscriptionKind.SAGA, derives = listOf("stock.item.Reserve")),
                    ),
                )
            },
        )
    }

    private val board = ProcessorBinding.project<OrderView>(
        "Board",
        "onOrder",
        OrderView::class.java,
        startFrom = StartFrom.BEGINNING,
        aggregates = listOf("sales.order"),
    ) { event, _ -> boarded += event }

    /** Picks an order: reserves its quantity of the item `picked-<order>`; an order of nothing derives nothing. */
    private val picker = ProcessorBinding.saga<OrderView>(
        "Picker",
        "onOrder",
        OrderView::class.java,
        startFrom = StartFrom.BEGINNING,
        aggregates = listOf("sales.order"),
        derives = listOf(Reserve::class.java),
    ) { event, context ->
        if (event.state.quantity == 0) {
            emptyList()
        } else {
            listOf(
                CommandRequest.to(ITEM.ref(event.ref.tenantId, "picked-${event.ref.id}"), Reserve(event.state.quantity))
            )
        }
    }

    private fun stockNode(): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(stock.model),
        stockManifest,
        InMemoryBackends(PARTITIONS),
        channel,
        remoteSignals = signals,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(STOCK to listOf(board, picker))),
        contracts = ContractDirectory(stockManifest, listOf(peer(salesManifest))),
        topics = SourceTopics(topics, PREFIX, topics, intake = true),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun salesNode(stockContract: Manifest = stockManifest): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest,
        InMemoryBackends(PARTITIONS),
        channel,
        remoteSignals = signals,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))) +
            PublishedSinks.topics(salesManifest, topics, PREFIX).factory,
        contracts = ContractDirectory(salesManifest, listOf(peer(stockContract))),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    /** Both services started, the stock node reading the order stream. */
    private suspend fun started(stockContract: Manifest = stockManifest): ServiceNode {
        stockNode().also {
            it.start()
            it.topicSubscriptions.getValue(ORDERS).awaitStarted()
        }
        return salesNode(stockContract).also { it.start() }
    }

    private fun ServiceNode.place(id: String, quantity: Int, vararg hops: Hop, timeout: Duration = 10.seconds) =
        context(SALES).let { context ->
            context.coordinator.stream(
                context.models.first(),
                Command(
                    "place-$id",
                    "place-$id",
                    ORDER.ref(TENANT, id),
                    PlaceOrder(quantity),
                    wait = WaitPlan(hops.toList(), timeout),
                ),
            )
        }

    @Test
    fun `a node whose saga sends to another context should not be built without a command transport`() {
        fun salesNodeWithout(contracts: ContractDirectory) = ServiceNode(
            SALES_SERVICE,
            "sales-1",
            "sales-1.internal:9090",
            listOf(orderModel(cancelled)),
            salesManifest,
            InMemoryBackends(PARTITIONS),
            channel,
            options = fastNodeOptions(),
            contracts = contracts,
        )
        val refused = assertThrows<WowConfigurationException> {
            salesNodeWithout(ContractDirectory(salesManifest, listOf(peer(stockManifest))))
        }
        refused.key.assert().isEqualTo("wow.transport")
        refused.message.assert()
            .contains("Node sales-1 has no command transport")
            .contains("$SALES/$SAGA sends commands to $STOCK")
        // Without the stock's Manifest, the saga's command is still another context's, one no Manifest declares.
        assertThrows<WowConfigurationException> { salesNodeWithout(ContractDirectory(salesManifest)) }
            .message.assert().contains("$SALES/$SAGA sends commands to ${ContractDirectory.UNKNOWN_CONTEXT}")
    }

    @Test
    fun `a node whose saga calls another context should be built only when its caller reaches it`() {
        // Decision D259: the saga calls the stock's Reserve instead of deriving it; a call needs no command topic.
        val calling = salesManifest.copyWith(
            contexts = salesManifest.contexts.map { context ->
                context.copyWith(
                    subscriptions = context.subscriptions.map {
                        it.copyWith(derives = emptyList(), calls = listOf(Manifest.schemaRef(Reserve::class.java.name)))
                    },
                )
            },
        )
        fun salesNode(contracts: ContractDirectory, caller: CommandCaller?) = ServiceNode(
            SALES_SERVICE,
            "sales-1",
            "sales-1.internal:9090",
            listOf(orderModel(cancelled)),
            calling,
            InMemoryBackends(PARTITIONS),
            channel,
            options = fastNodeOptions(),
            contracts = contracts,
            caller = caller,
        )
        val contracts = ContractDirectory(calling, listOf(peer(stockManifest)))
        val unreached = assertThrows<WowConfigurationException> { salesNode(contracts, null) }
        unreached.key.assert().isEqualTo("wow.peers.$STOCK.url")
        unreached.message.assert()
            .contains("Node sales-1 cannot make its sagas' calls")
            .contains("$SALES/$SAGA calls $STOCK, which the node's caller does not reach")
        val elsewhere = object : CommandCaller {
            override suspend fun call(call: OutgoingCall): CommandResult = error("not called")

            override fun reaches(context: String): Boolean = context != STOCK
        }
        assertThrows<WowConfigurationException> { salesNode(contracts, elsewhere) }.message.assert()
            .contains("which the node's caller does not reach")
        assertThrows<IllegalArgumentException> { salesNode(ContractDirectory(calling), elsewhere) }.message.assert()
            .contains("$SALES/$SAGA calls a command no Manifest of the service or its peers decides")
        // A caller that reaches the stock: the node is built, and needs no command transport for the call.
        salesNode(contracts) { error("not called") }.service.assert().isEqualTo(SALES_SERVICE)
    }

    @Test
    fun `the commands a saga sends within the contexts a node serves should need no transport`() {
        val contracts = ContractDirectory(salesManifest, listOf(peer(stockManifest)))
        contracts.commandsOutside(setOf(SALES, STOCK)).assert().isEmpty()
        contracts.commandsOutside(setOf(SALES)).assert().isEqualTo(mapOf("$SALES/$SAGA" to sortedSetOf(STOCK)))
        val lifecycle = salesManifest.copyWith(
            contexts = salesManifest.contexts.map { context ->
                context.copyWith(
                    subscriptions = context.subscriptions.map {
                        it.copyWith(derives = Lifecycle.COMMANDS.keys.toList())
                    },
                )
            },
        )
        ContractDirectory(lifecycle).commandsOutside(setOf(SALES)).assert().isEmpty()
    }

    @Test
    fun `a chain should follow the saga's commands into the other context and end at their COMMITTED`(): Unit =
        runBlocking {
            val sales = started()
            val results = sales.place("o-1", 2, Hop.committed(), SAGA_HOP, Hop.committed(STOCK)).toList()
            results.map { it.hop }.assert().containsExactly(Hop.committed(SALES), SAGA_HOP, Hop.committed(STOCK))
            results.all { it.status == CommandResult.Status.SUCCEEDED }.assert().isTrue()
            val saga = results[1]
            val reserved = results.last()
            saga.derived.assert().containsExactly(reserved.commandId)
            reserved.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-1"))
            reserved.version.assert().isEqualTo(1)
            reserved.function.kind.assert().isEqualTo(FunctionKind.COMMAND)
            reserved.function.context.assert().isEqualTo(STOCK)
            reserved.correlationId.assert().isEqualTo("place-o-1")
            // The stock intake signalled it to the sales node: the signal crossed services.
            signalled.single { it.result.hop == Hop.committed(STOCK) }.result.commandId.assert()
                .isEqualTo(reserved.commandId)
            sales.waits.size.assert().isEqualTo(0)
        }

    @Test
    fun `a rejection in the other context should end the wait at once, and still reach the saga by receipt`(): Unit =
        runBlocking {
            val sales = started()
            val began = TimeSource.Monotonic.markNow()
            val result = sales.place("o-2", AVAILABLE + 1, Hop.committed(), SAGA_HOP, Hop.committed(STOCK)).toList()
                .last()
            began.elapsedNow().assert().isLessThan(5.seconds)
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.hop.assert().isEqualTo(Hop.committed(STOCK))
            result.error!!.code.assert().isEqualTo(INSUFFICIENT)
            result.function.context.assert().isEqualTo(STOCK)
            // The receipt goes on as before: the saga's @OnRejected handler cancels the order.
            eventually { cancelled.size == 1 }
            rejections.single().commandId.assert().isEqualTo(result.commandId)
        }

    @Test
    fun `a command type the other context does not know should end the wait REJECTED at once`(): Unit = runBlocking {
        // Review X-5: the sales service has a newer stock contract; the stock intake rejects what it does not know.
        val sales = started(renamed(stockManifest, "Reserve", "ReserveV2"))
        val began = TimeSource.Monotonic.markNow()
        val result = sales.place("o-v2", 1, Hop.committed(), SAGA_HOP, Hop.committed(STOCK)).toList().last()
        began.elapsedNow().assert().isLessThan(5.seconds)
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.hop.assert().isEqualTo(Hop.committed(STOCK))
        result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        result.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-v2"))
        result.function.context.assert().isEqualTo(STOCK)
        result.function.processor.assert().isEqualTo("item")
        result.function.name.assert().isEqualTo("ReserveV2")
        result.correlationId.assert().isEqualTo("place-o-v2")
        eventually { cancelled.size == 1 }
        rejections.single().commandId.assert().isEqualTo(result.commandId)
    }

    @Test
    fun `a subscription of the published stream should signal its hop as a subscription of its own context`(): Unit =
        runBlocking {
            val sales = started()
            val result = sales.place("o-3", 1, Hop.handled(BOARD, STOCK)).toList().last()
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.handled(BOARD, STOCK))
            result.function.kind.assert().isEqualTo(FunctionKind.PROJECT)
            result.function.context.assert().isEqualTo(STOCK)
            result.function.processor.assert().isEqualTo("Board")
            result.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-3"))
            boarded.single { it.ref.id == "o-3" }.state.assert().isEqualTo(OrderView(1, false))
        }

    @Test
    fun `a saga of the published stream should carry the chain on into the commands it derives`(): Unit =
        runBlocking {
            val sales = started()
            val results = sales.place("o-4", 3, Hop.handled(PICKER, STOCK), Hop.committed(STOCK)).toList()
            results.map { it.hop }.assert().containsExactly(Hop.handled(PICKER, STOCK), Hop.committed(STOCK))
            results.all { it.status == CommandResult.Status.SUCCEEDED }.assert().isTrue()
            results.first().function.context.assert().isEqualTo(STOCK)
            results.first().derived.assert().containsExactly(results.last().commandId)
            results.last().ref.assert().isEqualTo(ITEM.ref(TENANT, "picked-o-4"))
        }

    @Test
    fun `a saga of the other context deriving nothing should end the chain early at its hop (D212)`(): Unit =
        runBlocking {
            val sales = started()
            val results = sales.place("o-6", 0, Hop.handled(PICKER, STOCK), Hop.committed(STOCK)).toList()
            val ended = results.single()
            ended.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            ended.hop.assert().isEqualTo(Hop.handled(PICKER, STOCK))
            ended.function.context.assert().isEqualTo(STOCK)
            ended.derived.assert().isEmpty()
            ended.chainEnded.assert().isTrue()
            ended.pending.assert().containsExactly(Hop.committed(STOCK))
            // The stock node signalled the saga's hop as it was: the chain's end is the origin's to tell.
            signalled.filter { it.result.hop == Hop.handled(PICKER, STOCK) }.map { it.result.chainEnded }
                .assert().isNotEmpty().doesNotContain(true)
            sales.waits.size.assert().isEqualTo(0)
        }

    @Test
    fun `a chain should be checked against the Manifests of both contexts, the saga's derives included`(): Unit =
        runBlocking {
            val sales = started()

            var count = 0

            suspend fun rejected(vararg hops: Hop): CommandResult = sales.place(
                "x-${++count}",
                1,
                *hops
            ).toList().single()

            // The sales saga derives only the stock's reservations (decision D138).
            rejected(Hop.committed(), SAGA_HOP, Hop.committed(SALES)).let {
                it.status.assert().isEqualTo(CommandResult.Status.REJECTED)
                it.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
                it.error!!.message.assert().contains("derives commands of [stock] only")
            }
            // A context the node does not know is let through (decision D190), but not past a saga that declares
            // what it derives, nor with a name that is no context's.
            rejected(Hop.committed(), SAGA_HOP, Hop.committed("nowhere")).error!!.message.assert()
                .contains("derives commands of [stock] only")
            rejected(Hop.committed(), Hop.handled("Some.saga", "Nowhere")).error!!.message.assert()
                .contains("names no context")
            // A subscription of the other context, known from its Manifest; a projection ends a chain.
            rejected(Hop.handled("Unknown.onOrder", STOCK)).error!!.message.assert().contains("unknown subscription")
            rejected(Hop.handled(BOARD, STOCK), Hop.committed(STOCK)).error!!.message.assert()
                .contains("Only a saga's hop may be followed")
            // The root command's own hops are of its context.
            rejected(Hop.committed(STOCK)).error!!.message.assert().contains("not of the command's context sales")
        }

    @Test
    fun `a signal the origin never gets should cost the wait its deadline, and nothing more`(): Unit = runBlocking {
        val sales = started()
        salesGone = true
        val began = TimeSource.Monotonic.markNow()
        val result = sales.place("o-5", 1, Hop.committed(), SAGA_HOP, Hop.committed(STOCK), timeout = 1.seconds)
            .toList().last()
        val elapsed = began.elapsedNow()
        elapsed.assert().isGreaterThanOrEqualTo(900.milliseconds)
        elapsed.assert().isLessThan(5.seconds)
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        // The last hop it reached is the saga's: the commit in the other context was never heard of.
        result.hop.assert().isEqualTo(SAGA_HOP)
        sales.waits.size.assert().isEqualTo(0)
        // The other context executed the command all the same, and signalled; the signal was lost.
        eventually { result.derived.single() in stock.decided }
        eventually { signalled.any { it.result.hop == Hop.committed(STOCK) } }
        // Once the node is back, a signal for the ended wait is dropped: no wait holds it.
        salesGone = false
        sales.waits.signal(signalled.first { it.result.hop == Hop.committed(STOCK) }).assert().isFalse()
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val ORDERS = PREFIX + "wow.sales.order.state"
        const val SAGA = "OrderSaga.onOrderPlaced"
        const val BOARD = "Board.onOrder"
        const val PICKER = "Picker.onOrder"
        val SAGA_HOP = Hop.handled(SAGA, SALES)
    }
}
