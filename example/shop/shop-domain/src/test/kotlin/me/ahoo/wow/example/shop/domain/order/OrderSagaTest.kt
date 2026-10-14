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

package me.ahoo.wow.example.shop.domain.order

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.example.inventory.api.ReserveStock
import me.ahoo.wow.example.shop.api.order.CancelOrder
import me.ahoo.wow.example.shop.api.order.OrderItem
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", OrderFixtures.ORDER_ID)
    private val stock = AggregateType("inventory", "stock")

    /** The saga under test, constructed with its own services only: it takes none. */
    private val handlers = orderSagaHandlers(Dependencies.NONE)

    private fun event(vararg events: Any) =
        stateEvent(order, OrderState(), *events, commandType = "example.order.CreateOrder")

    @Test
    fun `a placed order should reserve each product's stock in the inventory`() = sagaSpec(handlers) {
        whenEvent(event(OrderFixtures.orderCreated)) {
            expectHandledBy("onOrderCreated")
            commands.map { it.ref to it.body }.assert().containsExactly(
                stock.ref("tenant", "apple") to ReserveStock(OrderFixtures.ORDER_ID, 2),
                stock.ref("tenant", "pear") to ReserveStock(OrderFixtures.ORDER_ID, 1),
            )
            commands.first().causation.assert().isEqualTo(Causation("command-1", "example.order/tenant/order-1@1"))
            // Handling the commit again derives the same requests, which the inventory decides once.
            expectDeterministic()
        }
    }

    @Test
    fun `lines of one product should reserve their quantities together`() = sagaSpec(handlers) {
        val created = OrderFixtures.orderCreated.copy(
            items = listOf(
                OrderItem("order-1-1", "apple", OrderFixtures.APPLE_PRICE, 2),
                OrderItem("order-1-2", "apple", OrderFixtures.APPLE_PRICE, 3),
            ),
        )
        whenEvent(event(created)) { expectCommands(ReserveStock(OrderFixtures.ORDER_ID, 5)) }
    }

    @Test
    fun `another event of the order should reserve nothing`() = sagaSpec(handlers) {
        whenEvent(event(OrderPaid(BigDecimal.ONE, paid = false))) { expectNoCommand() }
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
        whenEvent(event(OrderFixtures.orderCreated)) {
            val pear = commands.single { it.ref.id == "pear" }
            whenRejected(pear, "inventory.stock.insufficient", "Only 0 of pear are available, not 1.") {
                expectHandledBy("onReserveRejected")
                expectCommand(CancelOrder("pear cannot be reserved: Only 0 of pear are available, not 1."), to = order)
                // The same receipt again derives the same request.
                expectDeterministic()
            }
        }
    }
}
