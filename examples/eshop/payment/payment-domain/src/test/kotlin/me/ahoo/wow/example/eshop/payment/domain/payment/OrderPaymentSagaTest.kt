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

package me.ahoo.wow.example.eshop.payment.domain.payment

import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.test.DEFAULT_TENANT
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.publishedEvent
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.sagaSpecTests
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class OrderPaymentSagaTest {
    private val order = EshopOrderingContract.Order.ref(DEFAULT_TENANT, ORDER)
    private val payment = EshopPaymentContract.Payment.ref(DEFAULT_TENANT, ORDER)

    /**
     * The saga under test, constructed alone: the module's other saga, `PaymentGatewaySaga`, takes the gateway, which
     * this one does not need.
     */
    private val handlers = orderPaymentSagaHandlers()

    /**
     * A commit of the order stream the ordering context publishes: its events as the ordering api's classes, its
     * state the ordering api's generated view of an order, which no model of the payment sources.
     */
    private fun orderEvent(event: Any) = stateEvent(
        order,
        OrderStateView(
            confirmedProducts = setOf("p-1"),
            items = listOf(OrderItem("p-1", TOTAL, 1)),
            status = OrderStatus.STOCK_CONFIRMED,
            totalAmount = TOTAL,
        ),
        event,
        version = 4,
        commandType = EshopOrderingContract.Order.Commands.CONFIRM_ORDER_STOCK,
    )

    /** The order's payment, requested for its total. */
    private fun ProcessorSpec.requested() = givenAggregate(paymentModel(), ORDER, PaymentRequested(ORDER, TOTAL))

    @Test
    fun `an order whose stock is confirmed should request its payment`() = sagaSpec(handlers) {
        whenEvent(orderEvent(OrderStockConfirmed)) {
            expectHandledBy("onOrderStockConfirmed")
            expectCommand(RequestPayment(ORDER, TOTAL), to = payment)
            expectDeterministic()
        }
        // An event the payment has no class of at its revision is a PublishedEvent: no OrderStockConfirmed, so no
        // handler takes the commit.
        whenEvent(orderEvent(publishedEvent("eshop-ordering.order.OrderStockConfirmed", revision = 2))) {
            expectHandledBy()
            expectNoCommand()
        }
    }

    @Test
    fun `a payment that succeeded should pay its order`() = sagaSpec(handlers) {
        requested()
        whenEvent(PaymentSucceeded("transaction-1"), by = ConfirmPayment::class) {
            expectHandledBy("onPaymentSucceeded")
            expectCommand(PayOrder(ORDER, TOTAL), to = order)
        }
    }

    @Test
    fun `a payment that failed should cancel its order`() = sagaSpec(handlers) {
        requested()
        whenEvent(PaymentFailed("declined"), by = FailPayment::class) {
            expectCommand(CancelOrder("The payment failed: declined"), to = order)
        }
        whenEvent(
            PaymentFailed("declined"),
            by = FailPayment::class,
            handler = "onPaymentSucceeded"
        ) { expectNoCommand() }
    }

    @Test
    fun `an order that refused its cancellation should park the payment`() = sagaSpec(handlers) {
        requested()
        whenEvent(PaymentFailed("declined"), by = FailPayment::class) {
            // No handler takes CancelOrder's rejections: only a paid order rejects it, and a failed payment paid
            // nothing, so the payment parks, to be looked at.
            whenRejected(commands.single(), code = EshopOrderingContract.Order.Errors.WRONG_STATUS) {
                expectHandledBy()
                expectParked()
            }
        }
    }

    @Test
    fun `an order that refused its payment should have the payment refunded`() = sagaSpec(handlers) {
        requested()
        whenEvent(PaymentSucceeded("transaction-1"), by = ConfirmPayment::class) {
            whenRejected(
                expectCommandEnvelope<PayOrder>(),
                code = EshopOrderingContract.Order.Errors.WRONG_STATUS,
                message = "The order is CANCELLED: only a STOCK_CONFIRMED order can be paid.",
            ) {
                expectHandledBy("onPayRejected")
                expectCommand(
                    RefundPayment(
                        "The order alice-1 refused the payment: " +
                            "The order is CANCELLED: only a STOCK_CONFIRMED order can be paid.",
                    ),
                    to = payment,
                )
                expectDeterministic()
            }
        }
    }

    /** A payment's outcomes as a tree of tests: the order paid, or refusing it, forked from the same handling. */
    @TestFactory
    fun `a payment's outcomes`() = sagaSpecTests(handlers) {
        requested()
        whenEvent(PaymentSucceeded("transaction-1"), by = ConfirmPayment::class) {
            val pay = expectCommand(PayOrder(ORDER, TOTAL), to = order)
            fork("order refused") {
                whenRejected(pay, code = EshopOrderingContract.Order.Errors.WRONG_STATUS, message = "Cancelled.") {
                    expectCommand(RefundPayment("The order alice-1 refused the payment: Cancelled."), to = payment)
                }
            }
        }
        whenEvent(PaymentFailed("declined"), by = FailPayment::class) {
            expectCommand(CancelOrder("The payment failed: declined"), to = order)
        }
    }

    private companion object {
        const val ORDER = "alice-1"
        val TOTAL = BigDecimal("29.00")
    }
}
