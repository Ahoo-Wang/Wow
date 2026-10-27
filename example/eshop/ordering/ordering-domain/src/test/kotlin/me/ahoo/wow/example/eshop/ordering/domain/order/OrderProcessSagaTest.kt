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

import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.EshopInventoryContract
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.sagaSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

/**
 * The order-process saga: validation, reservations in the inventory, and the compensation of a refusal. Every
 * handler of the saga reads the order, so each commit goes to all of them, as their subscriptions would hand it.
 */
class OrderProcessSagaTest {
    private val handlers = orderProcessSagaHandlers(Dependencies.NONE)
    private val model = orderModel(paymentPolicy = PaymentPolicy(30.minutes))
    private val stock = EshopInventoryContract.Stock.TYPE

    /** Two lines of the mug and one of the cap. */
    private val items = listOf(
        OrderItem("mug", BigDecimal.ONE, 2),
        OrderItem("cap", BigDecimal.TEN, 1),
        OrderItem("mug", BigDecimal.ONE, 3),
    )

    /** The order, submitted with [items]. */
    private fun ProcessorSpec.submitted() = givenAggregate(model, ORDER, OrderSubmitted(items))

    @Test
    fun `a submitted order should await its stock validation`() = sagaSpec(handlers) {
        val order = givenAggregate(model, ORDER)
        whenEvent(OrderSubmitted(items), by = SubmitOrder::class) {
            expectHandledBy("onOrderSubmitted", "onOrderAwaitingValidation", "onOrderCancelled", "onOrderShipped")
            expectCommand(AwaitStockValidation, to = order.ref)
        }
    }

    // region saga
    @Test
    fun `an order awaiting validation should reserve each product's stock in the inventory`() = sagaSpec(handlers) {
        val order = submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
            expectCommands(
                stock.ref(order.ref.tenantId, "mug") to ReserveStock(ORDER, 5),
                stock.ref(order.ref.tenantId, "cap") to ReserveStock(ORDER, 1),
            )
            // Handling the commit again derives the same requests, which the inventory decides once: the spec
            // gives each handling a fresh context, as the runtime does.
            expectDeterministic()
        }
    }
    // endregion

    @Test
    fun `a cancelled order should release its reservations`() = sagaSpec(handlers) {
        val order = submitted()
        whenEvent(OrderCancelled("cap cannot be reserved"), by = CancelOrder::class) {
            expectCommands(
                stock.ref(order.ref.tenantId, "mug") to ReleaseReservation(ORDER),
                stock.ref(order.ref.tenantId, "cap") to ReleaseReservation(ORDER),
            )
        }
    }

    @Test
    fun `a shipped order should commit its reservations`() = sagaSpec(handlers) {
        val order = submitted()
        whenEvent(OrderShipped, by = ShipOrder::class) {
            expectCommands(
                stock.ref(order.ref.tenantId, "mug") to CommitReservation(ORDER),
                stock.ref(order.ref.tenantId, "cap") to CommitReservation(ORDER),
            )
        }
    }

    @Test
    fun `a reservation the inventory committed should confirm the order's product`() = sagaSpec(handlers) {
        val order = submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
            val cap = commands.single { it.ref.id == "cap" }
            whenCommitted(cap, version = 3) {
                expectHandledBy("onReserveCommitted")
                expectCommand(ConfirmOrderStock("cap"), to = order.ref)
                // The same receipt again, or the receipt of the same request again, derives the same request.
                expectDeterministic()
            }
        }
    }

    // region rejected
    @Test
    fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
        val order = submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
            val cap = commands.single { it.ref.id == "cap" }
            whenRejected(cap, EshopInventoryContract.Stock.Errors.INSUFFICIENT, "Only 0 of cap are available, not 1.") {
                expectHandledBy("onReserveRejected")
                expectCommand(CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."), to = order.ref)
                // The same receipt again derives the same request.
                expectDeterministic()
            }
        }
    }
    // endregion

    @Test
    fun `a release the inventory refused should need no compensation`() = sagaSpec(handlers) {
        submitted()
        whenEvent(OrderCancelled("cap cannot be reserved"), by = CancelOrder::class) {
            whenRejected(commands.first(), FrameworkCodes.AGGREGATE_NOT_FOUND) {
                expectHandledBy("onReleaseRejected")
                expectNoCommand()
            }
        }
    }

    private companion object {
        const val ORDER = "alice-1"
    }
}
