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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.eachEvent
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Annotated processors' handlers running in a node (design 04 §1, §6; decision D62): the state event they receive,
 * a saga's derived commands and wait chains through its hop, parking on failure, and replays.
 */
class SubscriptionBindingsTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val decided = CopyOnWriteArrayList<String>()

    /** What the projection was handed, with the execution it ran as: one write, so a reader sees both or neither. */
    private val projected = CopyOnWriteArrayList<Pair<StateEvent<OrderState>, String>>()
    private val everything = CopyOnWriteArrayList<String>()
    private val carts = CopyOnWriteArrayList<List<String>>()

    /** Keeps what it is handed; fails for the order `boom`. */
    private val projection = ProcessorBinding.project(
        "OrderView",
        "onEvent",
        OrderState::class.java
    ) { event, context ->
        check(event.ref.id != "boom") { "${event.ref} cannot be projected." }
        projected += event to context.executionId.toString()
    }

    /** Every aggregate of the context, through `StateEvent<*>`. */
    private val audit = ProcessorBinding.react<Any>("Audit", "onEvent", null) { event, context ->
        everything += "${event.ref.type.name}@${event.version}:${context.executionId.subscription}"
        (event.state as? CartState)?.let { carts += it.items.toList() }
    }

    /** Removes the ordered items from the cart of the order's owner; derives nothing for an order without one. */
    private val saga = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        derives = listOf(RemoveItems::class.java),
    ) { event, context ->
        val owner = event.scope.ownerId ?: return@saga emptyList()
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, owner), RemoveItems(it.items))
        }
    }

    private val bindings = SubscriptionBindings(mapOf(SHOP to listOf(projection, audit, saga)))

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(decided)),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = bindings,
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun request(
        aggregate: String,
        command: String,
        json: String,
        id: String,
        owner: String? = null,
        wait: WaitPlan? = WaitPlan.COMMITTED,
    ) = ServerCommandRequest(
        context = SHOP,
        aggregate = aggregate,
        command = command,
        body = CommandBody.of(json),
        caller = Caller(TENANT, Scope(ownerId = owner)),
        aggregateId = id,
        wait = wait,
    )

    private suspend fun fill(owner: String, vararg items: String): CommandResult = node.gateway.execute(
        request("cart", "AddItems", """{"items":[${items.joinToString { "\"$it\"" }}]}""", owner, owner),
    )

    private suspend fun place(id: String, owner: String?, vararg items: String, wait: WaitPlan? = WaitPlan.COMMITTED) =
        node.gateway.send(
            request("order", "Place", """{"items":[${items.joinToString { "\"$it\"" }}]}""", id, owner, wait),
        )

    private suspend fun cartItems(owner: String): Set<String> {
        val ref = CART.ref(TENANT, owner)
        val version = backends.opened.getValue(SHOP).log.read(ref).toList().last().version
        return (node.context(SHOP).kernel.stateAt(cartModel(), ref, version)!!.state as CartState).items
    }

    private val sagaChain = WaitPlan.of(Hop.committed(), Hop.handled(saga.name), Hop.committed())

    @Test
    fun `a projection should get the decoded events and the state after the commit`(): Unit = runBlocking {
        node.start()
        node.context(SHOP).views.assert().isNotNull()
        place("o-1", "alice", "apple", "pear").collect { }
        eventually { projected.isNotEmpty() }
        val (event, execution) = projected.single()
        event.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-1"))
        event.version.assert().isEqualTo(1)
        event.commandType.assert().isEqualTo("shop.order.Place")
        event.events.assert().containsExactly(Placed(listOf("apple", "pear")))
        event.state.items.assert().containsExactly("apple", "pear")
        event.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        event.causation.assert().isNull()
        execution.assert().isEqualTo("OrderView.onEvent:shop.order/t/o-1@1")
        eventually { everything.contains("order@1:Audit.onEvent") }
    }

    @Test
    fun `a handler should get a state holding a set in the order it was written, not park it`(): Unit = runBlocking {
        node.start()
        // Not in hash order: read as a HashSet, the state would not write back as its view, and its handlers park.
        val items = (1..20).map { "item-$it" }.reversed()
        fill("dave", *items.toTypedArray()).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { carts.isNotEmpty() }
        carts.single().assert().isEqualTo(items)
        backends.opened.getValue(SHOP).ledger.isParked("Audit.onEvent", CART.ref(TENANT, "dave")).assert().isFalse()
    }

    @Test
    fun `a saga should remove the ordered items from the owner's cart, and the chain wait through its hop`(): Unit =
        runBlocking {
            node.start()
            fill("alice", "apple", "pear", "plum").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val results = node.gateway.send(
                request("order", "Place", """{"items":["apple","pear"]}""", "o-2", "alice", sagaChain),
            )
            val all = mutableListOf<CommandResult>()
            results.collect { all += it }
            val handled = all.single { it.hop.stage == Stage.HANDLED }
            handled.function.kind.assert().isEqualTo(FunctionKind.SAGA)
            val execution = ExecutionId(saga.name, ORDER.ref(TENANT, "o-2"), 1)
            val derived = ExecutionSagaContext.derivedId(execution, CART.ref(TENANT, "alice"), RemoveItems::class.java)
            handled.derived.assert().containsExactly(derived)
            val last = all.last()
            last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            last.commandId.assert().isEqualTo(derived)
            last.ref.assert().isEqualTo(CART.ref(TENANT, "alice"))
            last.hop.stage.assert().isEqualTo(Stage.COMMITTED)
            decided.assert().containsExactly(derived)
            val cart = backends.opened.getValue(SHOP).log.findByRequestId(CART, TENANT, derived)!!
            cart.causation!!.causationId.assert().isEqualTo("shop.order/t/o-2@1")
            cart.causation!!.correlationId.assert().isEqualTo(all.first().commandId)
        }

    @Test
    fun `a saga that derives nothing should complete its hop, and the chain, at once`(): Unit = runBlocking {
        node.start()
        val all = mutableListOf<CommandResult>()
        place("o-3", null, "apple", wait = sagaChain).collect { all += it }
        val last = all.last()
        last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        last.hop.subscription.assert().isEqualTo(saga.name)
        last.derived.assert().isEmpty()
        decided.assert().isEmpty()
    }

    @Test
    fun `a rejected derived command no handler takes should park the order at once and reject the wait at its hop`(): Unit =
        runBlocking {
            node.start()
            fill("bob", "apple")
            val all = mutableListOf<CommandResult>()
            place("o-4", "bob", FORBIDDEN_ITEM, wait = sagaChain).collect { all += it }
            // Decision D153: the wait ends at the saga's hop with the rejection, not a parking failure.
            val last = all.last()
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            last.error!!.code.assert().isEqualTo(FORBIDDEN)
            last.hop.subscription.assert().isEqualTo(saga.name)
            val ledger = backends.opened.getValue(SHOP).ledger
            eventually { ledger.isParked(saga.name, ORDER.ref(TENANT, "o-4")) }
            ledger.parked(saga.name, ORDER.ref(TENANT, "o-4"))!!.reason.assert().contains(FORBIDDEN)
            // A rejection is not retried: the cart decided the request once.
            decided.assert().hasSize(1)
        }

    @Test
    fun `a failing handler should park its aggregate and fail the wait for its hop`(): Unit = runBlocking {
        node.start()
        val last = place("boom", "alice", "apple", wait = WaitPlan.of(Hop.handled(projection.name))).first {
            it.hop.stage == Stage.HANDLED
        }
        last.status.assert().isEqualTo(CommandResult.Status.FAILED)
        eventually { backends.opened.getValue(SHOP).ledger.isParked(projection.name, ORDER.ref(TENANT, "boom")) }
        last.error!!.code.assert().isNotEqualTo(ResultCodes.WAIT_TIMEOUT)
    }

    @Test
    fun `replaying a commit should derive the same requests, which are not decided twice`(): Unit = runBlocking {
        node.start()
        fill("carol", "apple", "pear")
        place("o-5", "carol", "apple", wait = sagaChain).collect { }
        decided.assert().hasSize(1)
        cartItems("carol").assert().containsExactly("pear")

        // The saga's subscription handles the same entry again, as after a crash or an ownership change.
        val context = node.context(SHOP)
        val subscription = bindings.subscriptions(context).single { it.name == saga.name }
        val log = backends.opened.getValue(SHOP).log
        val stream = log.read(ORDER.ref(TENANT, "o-5")).first()
        val entry = subscription.decoder.decode(stream, null, null)
        val again = subscription.handle(entry)
        again.derived.assert().containsExactly(decided.single())
        // The kernel found the request committed: the cart did not decide it again.
        decided.assert().hasSize(1)
    }

    @Test
    fun `a handler of each event of a type should skip streams without one and get each one`(): Unit = runBlocking {
        val removed = CopyOnWriteArrayList<String>()
        // Decision D264: called once per ItemsRemoved of a cart's stream, with that call's key.
        val removals = ProcessorBinding.react(
            "Removals",
            "onRemoved",
            CartState::class.java,
            eventType = ItemsRemoved::class.java,
        ) { event, context ->
            eachEvent(event, ItemsRemoved::class.java, context) { typed, invocation ->
                removed += "${typed.event.items}:${invocation.invocationKey}"
            }
        }
        val typedNode = ServiceNode(
            SHOP_SERVICE,
            "node-2",
            "node-2.internal:9090",
            listOf(orderModel(), cartModel(decided)),
            shopManifest(),
            InMemoryBackends(partitions = 4),
            LoopbackClusterChannel(),
            options = fastNodeOptions(),
            subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga, removals))),
        )
        try {
            typedNode.start()
            typedNode.gateway.execute(
                request("cart", "AddItems", """{"items":["apple","pear"]}""", "alice", "alice"),
            ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            typedNode.gateway.send(
                request("order", "Place", """{"items":["apple"]}""", "o-9", "alice", sagaChain),
            ).collect { }
            eventually { removed.isNotEmpty() }
            // The cart's first commit holds no ItemsRemoved: skipped. Its second holds one, at index 0.
            removed.assert().containsExactly("[apple]:Removals.onRemoved:shop.cart/t/alice@2:0")
        } finally {
            typedNode.stop()
        }
    }

    @Test
    fun `the node should keep views only of the aggregates the handlers read`() {
        bindings.viewTypes(SHOP, listOf(orderModel(), cartModel())).assert().containsExactlyInAnyOrder(ORDER, CART)
        SubscriptionBindings(mapOf(SHOP to listOf(projection))).viewTypes(SHOP, listOf(orderModel(), cartModel()))
            .assert().containsExactly(ORDER)
        SubscriptionBindings.NONE.viewTypes(SHOP, listOf(orderModel())).assert().isEmpty()
        val plain = SubscriptionFactory { listOf(Subscription("plain", SubscriptionFilter(SHOP)) {}) }
        val both = plain + SubscriptionBindings(mapOf(SHOP to listOf(projection)))
        both.viewTypes(SHOP, listOf(orderModel(), cartModel())).assert().containsExactly(ORDER)
        both.subscriptions(node.context(SHOP)).map { it.name }.assert().containsExactly("plain", "OrderView.onEvent")
    }

    @Test
    fun `the subscriptions should be named after their handlers and filtered by their state`() {
        val subscriptions = bindings.subscriptions(node.context(SHOP)).associateBy { it.name }
        subscriptions.keys.assert().containsExactly("OrderView.onEvent", "Audit.onEvent", "CartSaga.onOrderPlaced")
        subscriptions.getValue("OrderView.onEvent").filter.aggregates.assert().containsExactly("order")
        subscriptions.getValue("Audit.onEvent").filter.aggregates.assert().containsExactlyInAnyOrder("order", "cart")
        subscriptions.getValue("Audit.onEvent").kind.assert().isEqualTo(FunctionKind.REACT)
        subscriptions.getValue("CartSaga.onOrderPlaced").kind.assert().isEqualTo(FunctionKind.SAGA)
        subscriptions.getValue("OrderView.onEvent").startFrom.assert()
            .isEqualTo(StartFrom.BEGINNING)
        subscriptions.getValue("CartSaga.onOrderPlaced").startFrom.assert()
            .isEqualTo(StartFrom.END)
        // Roadmap P.3: a handler's @Retry is its subscription's own budget.
        subscriptions.getValue("CartSaga.onOrderPlaced").retry.assert().isEqualTo(HandlerRetry(maxAttempts = 2))
        subscriptions.getValue("OrderView.onEvent").retry.assert().isNull()
        SubscriptionBindings(emptyMap()).subscriptions(node.context(SHOP)).assert().isEmpty()
    }

    @Test
    fun `a derived command should be signalled only when the saga's hop is waited for`() {
        val wait = me.ahoo.wow.spi.wait.WaitContext(
            "w",
            me.ahoo.wow.spi.wait.Origin("node-1", "a:1", SHOP_SERVICE),
            listOf(Hop.handled(saga.name, SHOP), Hop.committed(SHOP)),
            Long.MAX_VALUE,
        )
        DerivedCommands.waitOf(wait, SHOP, saga.name)!!.remaining.assert().containsExactly(Hop.committed(SHOP))
        DerivedCommands.waitOf(
            wait.copyWith(remaining = listOf(Hop.handled(saga.name))),
            SHOP,
            saga.name
        ).assert().isNull()
        DerivedCommands.waitOf(wait, "other", saga.name).assert().isNull()
        DerivedCommands.waitOf(wait, SHOP, "Other.saga").assert().isNull()
        DerivedCommands.waitOf(
            wait.copyWith(remaining = listOf(Hop.committed(SHOP))),
            SHOP,
            saga.name
        ).assert().isNull()
        DerivedCommands.waitOf(null, SHOP, saga.name).assert().isNull()
        DerivedCommands.waitOf(
            wait.copyWith(remaining = listOf(Hop.handled(saga.name), Hop.snapshot())),
            SHOP,
            saga.name,
        )!!.remaining.assert().containsExactly(Hop.snapshot())
    }

    @Test
    fun `a command derived for another context should fail the saga without a command transport`(): Unit = runBlocking {
        val derived = DerivedCommands(listOf(orderModel()), node.context(SHOP).coordinator)
        val elsewhere = Command("c", "r", me.ahoo.wow.api.aggregate.AggregateType("mail", "box").ref(TENANT, "m"), "x")
        runCatching { derived.execute(listOf(elsewhere), null) }.exceptionOrNull()!!.message.assert()
            .contains("no command transport")
        val unknown = Command("c", "r", me.ahoo.wow.api.aggregate.AggregateType(SHOP, "box").ref(TENANT, "m"), "x")
        runCatching { derived.execute(listOf(unknown), null) }.exceptionOrNull()!!.message.assert()
            .contains("an aggregate type its context does not have")
        derived.execute(emptyList(), null).executed.assert().isEmpty()
    }
}
