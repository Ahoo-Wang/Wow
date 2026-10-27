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
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.StateEvent
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

    @Test
    fun `a snapshot of an aggregate no module declares should fail, saying so`() {
        assertThrows<IllegalArgumentException> {
            memoryQueries { snapshot(AggregateType("inventory", "unknown"), "x", Stock(1)) }
        }.message.assert().contains("inventory.unknown")
    }

    /** Reserves what the stock has, read from the read side. */
    private val handlers = listOf(
        ProcessorBinding.saga("OrderSaga", "onOrder", Order::class.java) { event: StateEvent<Order>, context ->
            val sku = event.state.sku
            val available = context.queries.snapshots<Stock>().state(sku)?.state?.available ?: 0
            listOf(context.command(STOCK.ref(context.queries.tenantId, sku), Reserve(sku, available)))
        },
    )

    class Order(val sku: String)

    data class Reserve(val sku: String, val quantity: Int)

    private fun placed(sku: String) = stateEvent(
        AggregateType("shop", "order").ref("acme", "o-1"),
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
        fork {
            givenQueries(memoryQueries { snapshots(STOCK, "mug" to Stock(7), tenantId = "other") })
            // The stock of another tenant is not the handler's.
            whenEvent(placed("mug")) { expectCommand(Reserve("mug", 0)) }
        }
    }
}
