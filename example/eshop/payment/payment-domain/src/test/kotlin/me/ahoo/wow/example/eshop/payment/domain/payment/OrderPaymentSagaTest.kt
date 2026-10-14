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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.test.publishedEvent
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderPaymentSagaTest {
    private val order = AggregateType("eshop-ordering", "order").ref(TENANT, ORDER)
    private val payment = AggregateType("eshop-payment", "payment").ref(TENANT, ORDER)

    /**
     * The saga under test, constructed alone: the module's other saga, `PaymentGatewaySaga`, takes the gateway, which
     * this one does not need.
     */
    private val handlers = orderPaymentSagaHandlers(Dependencies.NONE)

    /** A commit of the order stream the ordering context publishes: its events come by name. */
    private fun orderEvent(name: String) =
        stateEvent(order, OrderView(TOTAL), publishedEvent(name), version = 4, commandType = CONFIRM_ORDER_STOCK)

    private fun paymentEvent(vararg events: Any) = stateEvent(
        payment,
        PaymentState().apply { onSourcing(PaymentRequested(ORDER, TOTAL)) },
        *events,
        version = 2,
    )

    @Test
    fun `an order whose stock is confirmed should request its payment`() = sagaSpec(handlers) {
        whenEvent(orderEvent(OrderPaymentSaga.ORDER_STOCK_CONFIRMED)) {
            expectHandledBy("onOrderStockConfirmed")
            expectCommand(RequestPayment(ORDER, TOTAL), to = payment)
            expectDeterministic()
        }
        whenEvent(orderEvent("eshop-ordering.order.OrderStockItemConfirmed")) { expectNoCommand() }
    }

    @Test
    fun `a payment that succeeded should pay its order`() = sagaSpec(handlers) {
        whenEvent(paymentEvent(PaymentSucceeded("transaction-1"))) {
            expectHandledBy("onPaymentSucceeded", "onPaymentFailed")
            expectCommand(PayOrder(ORDER, TOTAL), to = order)
        }
    }

    @Test
    fun `a payment that failed should cancel its order`() = sagaSpec(handlers) {
        whenEvent(paymentEvent(PaymentFailed("declined"))) {
            expectCommand(CancelOrder("The payment failed: declined"), to = order)
        }
        whenEvent(paymentEvent(PaymentFailed("declined")), handler = "onPaymentSucceeded") { expectNoCommand() }
    }

    @Test
    fun `an order that refused its payment should have the payment refunded`() = sagaSpec(handlers) {
        whenEvent(paymentEvent(PaymentSucceeded("transaction-1"))) {
            whenRejected(
                expectCommand<PayOrder>(),
                code = "eshop-ordering.order.wrong-status",
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

    private companion object {
        const val TENANT = "tenant"
        const val ORDER = "alice-1"
        const val CONFIRM_ORDER_STOCK = "eshop-ordering.order.ConfirmOrderStock"
        val TOTAL = BigDecimal("29.00")
    }
}
