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

package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The order-process saga: validation, reservations in the inventory, and the compensation of a refusal. Every
 * handler of the saga reads the order, so each commit goes to all of them, as their subscriptions would hand it.
 */
class OrderProcessSagaTest {
    private val order = AggregateType("eshop-ordering", "order").ref(TENANT, ORDER)
    private val stock = AggregateType("eshop-inventory", "stock")
    private val handlers = orderProcessSagaHandlers(Dependencies.NONE)

    /** The order's state: two lines of the mug and one of the cap. */
    private val state = OrderState(ORDER).apply {
        onSourcing(
            OrderSubmitted(
                "alice",
                listOf(
                    OrderItem("mug", BigDecimal.ONE, 2),
                    OrderItem("cap", BigDecimal.TEN, 1),
                    OrderItem("mug", BigDecimal.ONE, 3),
                ),
            ),
        )
    }

    private fun event(vararg events: Any) =
        stateEvent(order, state, *events, version = 2, commandType = "eshop-ordering.order.X")

    @Test
    fun `a submitted order should await its stock validation`() = sagaSpec(handlers) {
        whenEvent(event(OrderSubmitted("alice", state.items))) {
            expectHandledBy("onOrderSubmitted", "onOrderAwaitingValidation", "onOrderCancelled", "onOrderShipped")
            expectCommand(AwaitStockValidation, to = order)
        }
    }

    @Test
    fun `an order awaiting validation should reserve each product's stock in the inventory`() = sagaSpec(handlers) {
        whenEvent(event(OrderAwaitingValidation)) {
            commands.map { it.ref to it.body }.assert().containsExactly(
                stock.ref(TENANT, "mug") to ReserveStock(ORDER, 5),
                stock.ref(TENANT, "cap") to ReserveStock(ORDER, 1),
            )
            // Handling the commit again derives the same requests, which the inventory decides once: the spec
            // gives each handling a fresh context, as the runtime does.
            expectDeterministic()
        }
    }

    @Test
    fun `a cancelled order should release its reservations`() = sagaSpec(handlers) {
        whenEvent(event(OrderCancelled("cap cannot be reserved"))) {
            commands.map { it.ref to it.body }.assert().containsExactly(
                stock.ref(TENANT, "mug") to ReleaseReservation(ORDER),
                stock.ref(TENANT, "cap") to ReleaseReservation(ORDER),
            )
        }
    }

    @Test
    fun `a shipped order should commit its reservations`() = sagaSpec(handlers) {
        whenEvent(event(OrderShipped)) {
            commands.map { it.ref to it.body }.assert().containsExactly(
                stock.ref(TENANT, "mug") to CommitReservation(ORDER),
                stock.ref(TENANT, "cap") to CommitReservation(ORDER),
            )
        }
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
        whenEvent(event(OrderAwaitingValidation)) {
            val cap = commands.single { it.ref.id == "cap" }
            whenRejected(cap, "eshop-inventory.stock.insufficient", "Only 0 of cap are available, not 1.") {
                expectHandledBy("onReserveRejected")
                expectCommand(CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."), to = order)
                // The same receipt again derives the same request.
                expectDeterministic()
            }
        }
    }

    @Test
    fun `a release the inventory refused should need no compensation`() = sagaSpec(handlers) {
        whenEvent(event(OrderCancelled("cap cannot be reserved"))) {
            whenRejected(commands.first(), "wow.aggregate.not-found") {
                expectHandledBy("onReleaseRejected")
                expectNoCommand()
            }
        }
    }

    private companion object {
        const val TENANT = "tenant"
        const val ORDER = "alice-1"
    }
}
