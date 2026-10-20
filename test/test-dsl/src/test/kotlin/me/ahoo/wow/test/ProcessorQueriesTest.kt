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

package me.ahoo.wow.test

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import org.junit.jupiter.api.Test

/** A saga's handlers read through their context's queries (decision D259), which a test serves with a fake. */
class ProcessorQueriesTest {
    data class Reserve(val sku: String, val quantity: Int)
    data class Confirm(val quantity: Int)

    class Order(val sku: String)

    class Stock(val available: Int)

    private val order = AggregateType("shop", "order").ref("acme", "order-1")
    private val stock = AggregateType("inventory", "stock")

    /** Reserves what the stock has, read from the read side; confirms with what it has once reserved. */
    private val handlers = listOf(
        ProcessorBinding.saga(
            "OrderSaga",
            "onOrder",
            Order::class.java,
            commits = listOf(
                CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java) { committed, context ->
                    val available = context.queries.snapshots<Stock>().state(committed.ref.id)!!.state.available
                    listOf(context.command(committed.executionId.ref, Confirm(available)))
                },
            ),
        ) { event: StateEvent<Order>, context ->
            val sku = event.state.sku
            val available = context.queries.snapshots<Stock>().state(sku)?.state?.available ?: 0
            listOf(context.command(stock.ref(context.queries.tenantId, sku), Reserve(sku, available)))
        },
    )

    private fun placed() = stateEvent(order, Order("apple"), Reserve("apple", 1))

    private fun queries(available: Int): QueryApi {
        val stocks = mockk<me.ahoo.wow.api.read.SnapshotQueries<Stock>> {
            coEvery { state(any<String>()) } answers {
                AggregateState.of(stock.ref("acme", firstArg()), 1, Scope.NONE, false, emptyMap(), Stock(available))
            }
        }
        val tenant = mockk<me.ahoo.wow.api.read.Queries> { every { snapshots(Stock::class.java) } returns stocks }
        return mockk { every { of(Caller.of(tenantId = "acme")) } returns tenant }
    }

    @Test
    fun `a saga's reads should be served by the queries the test gives, in forks too`() = sagaSpec(handlers) {
        givenQueries(queries(available = 7))
        whenEvent(placed()) {
            expectCommand(Reserve("apple", 7), to = stock.ref("acme", "apple"))
        }
        fork {
            givenQueries(queries(available = 2))
            whenEvent(placed()) { expectCommand(Reserve("apple", 2)) }
        }
        fork {
            whenEvent(placed()) { expectCommand(Reserve("apple", 7)) }
        }
    }

    @Test
    fun `a receipt's handler should read through the same queries`() = sagaSpec(handlers) {
        givenQueries(queries(available = 5))
        whenEvent(placed()) { }
        lateinit var reserve: me.ahoo.wow.api.decide.Command<*>
        whenEvent(placed()) { reserve = commands.single() }
        whenCommitted(reserve) { expectCommand(Confirm(5), to = order) }
    }

    @Test
    fun `without queries a handler that reads should fail`() = sagaSpec(handlers) {
        whenEvent(placed()) { expectError<UnsupportedOperationException>() }
    }
}
