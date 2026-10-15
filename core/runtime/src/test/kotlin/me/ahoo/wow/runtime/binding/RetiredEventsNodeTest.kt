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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A log holding events the aggregate retired (decision D219, 9.x `DroppedEvent`) in a running node: the aggregate
 * loads past them, a handler never sees them, and its subscription moves past a stream holding only them, parking
 * nothing.
 */
class RetiredEventsNodeTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val projected = CopyOnWriteArrayList<String>()
    private val sagas = CopyOnWriteArrayList<String>()

    /** The cart of the binding fixtures, which once had an `Abandoned` event: its class is gone, its name retired. */
    private val model: AggregateModel<CartState> = aggregate<CartState>(SHOP, "cart") {
        initial { CartState() }
        decide<AddItems>(Creation.ALLOWED) { _, body -> Decision.events(ItemsAdded(body.items)) }
        decide<RemoveItems> { _, body -> Decision.events(ItemsRemoved(body.items)) }
        evolve<ItemsAdded> { items = items + it.items }
        evolve<ItemsRemoved> { items = items - it.items.toSet() }
        retired(ABANDONED)
    }

    private val projection = ProcessorBinding.project("CartView", "onEvent", CartState::class.java) { event, _ ->
        projected += "${event.ref.id}@${event.version}:${event.events}"
    }

    private val saga = ProcessorBinding.saga("CartSaga", "onEvent", CartState::class.java) { event, _ ->
        sagas += "${event.ref.id}@${event.version}:${event.events}"
        emptyList()
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), model),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(projection, saga))),
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private suspend fun add(id: String, vararg items: String): CommandResult = node.gateway.execute(
        CommandRequest(
            context = SHOP,
            aggregate = "cart",
            command = "AddItems",
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope.NONE),
            aggregateId = id,
            wait = WaitPlan.COMMITTED,
        ),
    )

    /** Appends a stream of [events] to the cart [id] at [version], as the log of an older version left it. */
    private suspend fun append(id: String, version: Int, vararg events: EventRecord) {
        val stream = EventStreamRecord(
            ref = CART.ref(TENANT, id),
            version = version,
            commandId = "old-$version",
            requestId = "old-$version",
            commandType = "shop.cart.Abandon",
            commitTime = System.currentTimeMillis(),
            events = events.toList(),
            scope = Scope.NONE,
        )
        backends.opened.getValue(SHOP).log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `a history holding retired events should load, and its subscriptions pass over them`(): Unit = runBlocking {
        node.start()
        add("c-1", "apple").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val abandoned = EventRecord(ABANDONED, 1, """{"reason":"late"}""")
        append("c-1", 2, abandoned)
        append("c-1", 3, abandoned, EventRecord("shop.cart.ItemsAdded", 1, """{"items":["pear"]}"""))

        // The aggregate loads past them: version 4 follows, on the state the other events left.
        val added = add("c-1", "plum")
        added.status.assert().withFailMessage { added.toString() }.isEqualTo(CommandResult.Status.SUCCEEDED)
        val ref = CART.ref(TENANT, "c-1")
        val state = node.context(SHOP).kernel.stateAt(model, ref, 4)!!
        (state.state as CartState).items.assert().containsExactly("apple", "pear", "plum")

        // The handlers never see a retired event, nor the stream holding only one; nothing parks.
        val expected = listOf(
            "c-1@1:[ItemsAdded(items=[apple])]",
            "c-1@3:[ItemsAdded(items=[pear])]",
            "c-1@4:[ItemsAdded(items=[plum])]",
        )
        eventually { projected.size == 3 && sagas.size == 3 }
        projected.assert().isEqualTo(expected)
        sagas.assert().isEqualTo(expected)
        val ledger = backends.opened.getValue(SHOP).ledger
        ledger.isParked("CartView.onEvent", ref).assert().isFalse()
        ledger.isParked("CartSaga.onEvent", ref).assert().isFalse()
    }

    private companion object {
        const val ABANDONED = "shop.cart.Abandoned"
    }
}
