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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.example.eshop.ordering.api.OrderingContext
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderPaid
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockItemConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

/** The order's state machine on the kernel, through the model the compiler generated, with a 30-minute grace period. */
class OrderSpec {
    private val model = orderModel(OrderingContext.NAME, PaymentPolicy(GRACE))

    private fun orderSpec(block: AggregateSpec<OrderState>.() -> Unit) = aggregateSpec(model, id = ORDER, block = block)

    /** The order's stock confirmed: every product reserved, the payment awaited. */
    private fun AggregateSpec<OrderState>.givenStockConfirmed() {
        given(submitted)
        given(OrderAwaitingValidation)
        given(OrderStockItemConfirmed(MUG))
        given(OrderStockItemConfirmed(CAP), OrderStockConfirmed)
    }

    @Test
    fun `submitting should create the order`() = orderSpec {
        // The basket's saga sends it in its buyer's scope (decision D189): the order belongs to the buyer.
        whenCommand(SubmitOrder(items), scope = Scope(ownerId = BUYER)) {
            expectEvents(submitted)
            expectScope(Scope(ownerId = BUYER))
            expectState {
                status.assert().isEqualTo(OrderStatus.SUBMITTED)
                totalAmount.assert().isEqualByComparingTo(TOTAL)
            }
        }
    }

    @Test
    fun `an order without items, or with an invalid one, should be rejected`() = orderSpec {
        whenCommand(SubmitOrder(emptyList())) { expectRejected(OrderRejections.INVALID_ITEMS) }
        whenCommand(SubmitOrder(listOf(OrderItem(MUG, MUG_PRICE, 0)))) {
            expectRejected(OrderRejections.INVALID_ITEMS)
        }
        whenCommand(SubmitOrder(listOf(OrderItem(MUG, BigDecimal("-1"), 1)))) {
            expectRejected(OrderRejections.INVALID_ITEMS)
        }
    }

    @Test
    fun `a submitted order should await its stock validation once`() = orderSpec {
        given(submitted)
        whenCommand(AwaitStockValidation) {
            expectEvents(OrderAwaitingValidation)
            expectState { status.assert().isEqualTo(OrderStatus.AWAITING_VALIDATION) }
        }
        whenCommand(AwaitStockValidation) { expectUnchanged() }
    }

    @Test
    fun `confirming a product should wait for the others`() = orderSpec {
        given(submitted, OrderAwaitingValidation)
        whenCommand(ConfirmOrderStock(MUG)) {
            expectEvents(OrderStockItemConfirmed(MUG))
            expectState {
                status.assert().isEqualTo(OrderStatus.AWAITING_VALIDATION)
                confirmedProducts.assert().containsExactly(MUG)
            }
        }
        // The same confirmation again, read twice from the stream.
        whenCommand(ConfirmOrderStock(MUG)) { expectUnchanged() }
        // A product the order does not hold.
        whenCommand(ConfirmOrderStock("hat")) { expectUnchanged() }
    }

    @Test
    fun `the last confirmation should confirm the stock and schedule the payment grace period`() = orderSpec {
        given(submitted, OrderAwaitingValidation, OrderStockItemConfirmed(MUG))
        whenCommand(ConfirmOrderStock(CAP)) {
            expectEvents(OrderStockItemConfirmed(CAP), OrderStockConfirmed)
            expectScheduled(Order.PAYMENT_GRACE_PERIOD, CancelOrder(Order.PAYMENT_TIMEOUT), ScheduleRule.after(GRACE))
            expectState { status.assert().isEqualTo(OrderStatus.STOCK_CONFIRMED) }
        }
    }

    @Test
    fun `a confirmation for a cancelled order should change nothing`() = orderSpec {
        given(submitted, OrderAwaitingValidation, OrderCancelled("cap cannot be reserved"))
        whenCommand(ConfirmOrderStock(MUG)) {
            expectUnchanged()
            expectState { status.assert().isEqualTo(OrderStatus.CANCELLED) }
        }
    }

    @Test
    fun `an unpaid order should cancel itself once its grace period has passed`() = orderSpec {
        given(submitted, OrderAwaitingValidation, OrderStockItemConfirmed(MUG))
        whenCommand(
            ConfirmOrderStock(CAP)
        ) { expectScheduled(Order.PAYMENT_GRACE_PERIOD, CancelOrder(Order.PAYMENT_TIMEOUT), ScheduleRule.after(GRACE)) }
        advanceTime(GRACE) {
            expectEvents(OrderCancelled(Order.PAYMENT_TIMEOUT))
            expectState {
                status.assert().isEqualTo(OrderStatus.CANCELLED)
                cancelReason.assert().isEqualTo(Order.PAYMENT_TIMEOUT)
            }
        }
    }

    @Test
    fun `paying should pay the order and cancel its grace period`() = orderSpec {
        givenStockConfirmed()
        whenCommand(PayOrder(PAYMENT, TOTAL)) {
            expectEvents(OrderPaid(PAYMENT, TOTAL))
            expectScheduleCancelled(Order.PAYMENT_GRACE_PERIOD)
            expectState {
                status.assert().isEqualTo(OrderStatus.PAID)
                paymentId.assert().isEqualTo(PAYMENT)
            }
        }
    }

    @Test
    fun `a paid order should not be cancelled when its grace period has passed`() = orderSpec {
        given(submitted, OrderAwaitingValidation, OrderStockItemConfirmed(MUG))
        whenCommand(
            ConfirmOrderStock(CAP)
        ) { expectEventTypes(OrderStockItemConfirmed::class.java, OrderStockConfirmed::class.java) }
        whenCommand(PayOrder(PAYMENT, TOTAL)) { expectScheduleCancelled(Order.PAYMENT_GRACE_PERIOD) }
        // The schedule is cancelled, so nothing is due once the period has passed; were it sent late anyway, the
        // order would reject it.
        whenCommand(CancelOrder(Order.PAYMENT_TIMEOUT)) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    @Test
    fun `a payment of another amount should be rejected`() = orderSpec {
        givenStockConfirmed()
        whenCommand(PayOrder(PAYMENT, BigDecimal.ONE)) { expectRejected(OrderRejections.WRONG_AMOUNT) }
    }

    @Test
    fun `an order whose stock is not confirmed should not be paid`() = orderSpec {
        given(submitted, OrderAwaitingValidation)
        whenCommand(PayOrder(PAYMENT, TOTAL)) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    @Test
    fun `a paid order should be shipped`() = orderSpec {
        givenStockConfirmed()
        given(OrderPaid(PAYMENT, TOTAL))
        whenCommand(ShipOrder) {
            expectEvents(OrderShipped)
            expectState { status.assert().isEqualTo(OrderStatus.SHIPPED) }
        }
    }

    @Test
    fun `an order not paid should not be shipped`() = orderSpec {
        givenStockConfirmed()
        whenCommand(ShipOrder) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    @Test
    fun `an order awaiting its payment should be cancelled with its grace period`() = orderSpec {
        givenStockConfirmed()
        whenCommand(CancelOrder("changed my mind")) {
            expectEvents(OrderCancelled("changed my mind"))
            expectScheduleCancelled(Order.PAYMENT_GRACE_PERIOD)
        }
        whenCommand(CancelOrder("again")) { expectUnchanged() }
    }

    @Test
    fun `an order awaiting validation should be cancelled`() = orderSpec {
        given(submitted, OrderAwaitingValidation)
        whenCommand(CancelOrder("cap cannot be reserved")) {
            expectEvents(OrderCancelled("cap cannot be reserved"))
            expectState { cancelReason.assert().isEqualTo("cap cannot be reserved") }
        }
    }

    @Test
    fun `a paid or shipped order should not be cancelled`() = orderSpec {
        givenStockConfirmed()
        given(OrderPaid(PAYMENT, TOTAL))
        whenCommand(CancelOrder(Order.PAYMENT_TIMEOUT)) { expectRejected(OrderRejections.WRONG_STATUS) }
        given(OrderShipped)
        whenCommand(CancelOrder("too late")) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    private companion object {
        const val ORDER = "alice-1"
        const val BUYER = "alice"
        const val MUG = "mug"
        const val CAP = "cap"
        const val PAYMENT = "payment-1"
        val GRACE = 30.minutes
        val MUG_PRICE = BigDecimal("8.50")
        val CAP_PRICE = BigDecimal("12.00")
        val items = listOf(OrderItem(MUG, MUG_PRICE, 2), OrderItem(CAP, CAP_PRICE, 1))
        val submitted = OrderSubmitted(items)
        val TOTAL = BigDecimal("29.00")
    }
}
