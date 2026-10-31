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

package me.ahoo.wow.test.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventType
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.test.query.InventoryModels.Cart
import me.ahoo.wow.test.query.InventoryModels.Companion.CART
import me.ahoo.wow.test.query.InventoryModels.Companion.STOCK
import me.ahoo.wow.test.query.InventoryModels.Stock
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The read side of the modules on the class path, the inventory's ([InventoryModels]), run by the query engine. */
class MemoryQueriesTest {
    @Test
    fun `the snapshots given should be what the queries list, filter and read by id`(): Unit = runBlocking {
        val queries = memoryQueries {
            snapshots(STOCK, "mug" to Stock(7), "cup" to Stock(0), tenantId = "acme")
            snapshot(STOCK, "pen", Stock(3), tenantId = "other")
        }
        queries.trusted().snapshots<Stock>().list(listQuery { }).toList().map { it.ref.id }
            .assert().containsExactlyInAnyOrder("mug", "cup", "pen")
        val acme = queries.of(Caller.of(tenantId = "acme")).snapshots<Stock>()
        acme.list(listQuery { filter { "state.available" gt 0 } }).toList().map { it.state }
            .assert().containsExactly(Stock(7))
        acme.state("mug")!!.state.assert().isEqualTo(Stock(7))
        // Another tenant's snapshot is not the caller's.
        acme.list(listQuery { }).toList().map { it.ref.id }.assert().containsExactlyInAnyOrder("mug", "cup")
    }

    @Test
    fun `an owner's snapshots should be hidden from another owner by the scope policy`(): Unit = runBlocking {
        val queries = memoryQueries {
            snapshot(CART, "c-1", Cart(2), tenantId = "acme", owner = Scope("alice"))
            snapshot(CART, "c-2", Cart(5), tenantId = "acme", owner = Scope("bob"))
        }
        val alice = queries.of(Caller.of(tenantId = "acme", scope = Scope("alice"), subject = "alice"))
            .snapshots<Cart>()
        alice.list(listQuery { }).toList().map { it.ref.id }.assert().containsExactly("c-1")
        alice.state("c-1")!!.state.assert().isEqualTo(Cart(2))
        // A handler of a commit of the tenant reads it whole, as on a node.
        queries.tenant("acme").snapshots<Cart>().list(listQuery { }).toList().map { it.ref.id }
            .assert().containsExactlyInAnyOrder("c-1", "c-2")
    }

    data class Received(val quantity: Int)

    data class Shipped(val quantity: Int)

    data class Lost(val quantity: Int)

    /** The stock as its module builds it: received and shipped are its events, lost is none. */
    private val stockModel = AggregateModel(
        type = STOCK,
        stateType = Stock::class.java,
        initial = { Stock(0) },
        deciders = emptyMap(),
        evolvers = mapOf(
            Received::class.java to { state: Stock, event: Any -> Stock(state.available + (event as Received).quantity) },
            Shipped::class.java to { state: Stock, event: Any -> Stock(state.available - (event as Shipped).quantity) },
        ),
        eventTypes = listOf(
            EventType("received", 1, Received::class.java),
            EventType("shipped", 1, Shipped::class.java)
        ),
        commandTypes = emptyList(),
    )

    @Test
    fun `a snapshot built from events should hold the state the model's sourcing leaves`(): Unit = runBlocking {
        val queries = memoryQueries {
            snapshot(stockModel, "mug", Received(10), Shipped(3), tenantId = "acme")
            snapshot(stockModel, "cup", Received(2), tenantId = "acme", owner = Scope("alice"), deleted = true)
            // A map is written without the state class: the misspelled field is dropped, and the default read back.
            snapshot(STOCK, "pen", mapOf("availabel" to 3), tenantId = "acme")
        }
        val stocks = queries.tenant("acme").snapshots<Stock>()
        val mug = stocks.state("mug")!!
        mug.state.assert().isEqualTo(Stock(7))
        mug.version.assert().isEqualTo(2)
        stocks.list(listQuery { filter { "state.available" gt 5 } }).toList().map { it.ref.id }
            .assert().containsExactly("mug")
        stocks.state("pen")!!.state.assert().isEqualTo(Stock(0))
        val deleted = listQuery { filter { deletion(DeletionState.DELETED) } }
        queries.trusted().snapshots<Stock>().list(deleted).toList().single().let {
            it.state.assert().isEqualTo(Stock(2))
            it.scope.assert().isEqualTo(Scope("alice"))
            it.deleted.assert().isTrue()
        }
    }

    @Test
    fun `an event the model has no sourcing function for should be refused`() {
        assertThrows<IllegalArgumentException> {
            memoryQueries { snapshot(stockModel, "mug", Received(1), Lost(1)) }
        }.message.assert().contains(Lost::class.java.name, "inventory.stock")
        assertThrows<IllegalArgumentException> {
            memoryQueries { snapshot(stockModel, "mug") }
        }.message.assert().contains("at least one event")
    }

    @Test
    fun `a snapshot of an aggregate no module declares should fail, saying so`() {
        assertThrows<IllegalArgumentException> {
            memoryQueries { snapshot(AggregateType("inventory", "unknown"), "x", Stock(1)) }
        }.message.assert().contains("inventory.unknown")
    }

    /** Reserves what the stock has, read from the read side. */
    private val handlers = listOf(
        ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            derives = listOf(Reserve::class.java),
        ) { event: StateEvent<Order>, context ->
            val sku = event.state.sku
            val available = context.queries.snapshots<Stock>().state(sku)?.state?.available ?: 0
            listOf(CommandRequest.to(STOCK.ref(context.queries.tenantId, sku), Reserve(sku, available)))
        },
    )

    class Order(val sku: String)

    data class Reserve(val sku: String, val quantity: Int)

    private fun placed(sku: String, tenantId: String = "acme") = stateEvent(
        AggregateType("shop", "order").ref(tenantId, "o-1"),
        Order(sku),
        Reserve(sku, 1)
    )

    @Test
    fun `a processor spec should read the snapshots it is given`() = sagaSpec(handlers) {
        givenSnapshots(STOCK, "mug" to Stock(7), tenantId = "acme")
        whenEvent(placed("mug")) {
            expectCommand(Reserve("mug", 7), to = STOCK.ref("acme", "mug"))
        }
        whenEvent(placed("cup")) {
            expectCommand(Reserve("cup", 0))
        }
        fork("from events") {
            // Built from the stock's events, in the default tenant.
            givenSnapshots(stockModel, "mug", Received(10), Shipped(4))
            whenEvent(placed("mug", Caller.DEFAULT_TENANT)) {
                expectCommand(Reserve("mug", 6), to = STOCK.ref(Caller.DEFAULT_TENANT, "mug"))
            }
            whenEvent(placed("mug")) { expectCommand(Reserve("mug", 0)) }
        }
        fork {
            givenQueries(memoryQueries { snapshots(STOCK, "mug" to Stock(7), tenantId = "other") })
            // The stock of another tenant is not the handler's.
            whenEvent(placed("mug")) { expectCommand(Reserve("mug", 0)) }
        }
    }
}
