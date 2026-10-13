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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.rejectionContextOf
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/** The order-payment saga: from the ordering's confirmed stock to the payment, and from the payment to the order. */
class OrderPaymentSagaTest {
    private val order = AggregateType("eshop-ordering", "order").ref(TENANT, ORDER)
    private val payment = AggregateType("eshop-payment", "payment").ref(TENANT, ORDER)
    private val saga = OrderPaymentSaga()
    private val json = JsonMapper.builder().build()

    private fun orderEvent(name: String) = StateEvent(
        order,
        4,
        "command-4",
        "request-4",
        "eshop-ordering.order.ConfirmOrderStock",
        1L,
        listOf(StateEventPayload(name, 1, json.createObjectNode())),
        OrderView(TOTAL),
    )

    private fun paymentEvent(vararg events: Any): StateEvent<PaymentState> {
        val state = PaymentState(ORDER).apply { onSourcing(PaymentRequested(ORDER, TOTAL)) }
        return StateEvent(payment, 2, "command-2", "request-2", "eshop-payment.payment.X", 1L, events.toList(), state)
    }

    private fun <S> context(subscription: String, event: StateEvent<S>) =
        ExecutionSagaContext(ExecutionId(subscription, event.ref, event.version), event)

    @Test
    fun `an order whose stock is confirmed should request its payment`() {
        val event = orderEvent(OrderPaymentSaga.ORDER_STOCK_CONFIRMED)
        val command = saga.onOrderStockConfirmed(event, context("OrderPaymentSaga.onOrderStockConfirmed", event))!!
        command.ref.assert().isEqualTo(payment)
        command.body.assert().isEqualTo(RequestPayment(ORDER, TOTAL))
        val other = orderEvent("eshop-ordering.order.OrderStockItemConfirmed")
        saga.onOrderStockConfirmed(other, context("OrderPaymentSaga.onOrderStockConfirmed", other)).assert().isNull()
    }

    @Test
    fun `a payment that succeeded should pay its order`() {
        val event = paymentEvent(PaymentSucceeded("transaction-1"))
        val command = saga.onPaymentSucceeded(event, context("OrderPaymentSaga.onPaymentSucceeded", event))!!
        command.ref.assert().isEqualTo(order)
        command.body.assert().isEqualTo(PayOrder(ORDER, TOTAL))
        saga.onPaymentSucceeded(
            paymentEvent(PaymentFailed("no")),
            context("OrderPaymentSaga.onPaymentSucceeded", event)
        )
            .assert().isNull()
    }

    @Test
    fun `a payment that failed should cancel its order`() {
        val event = paymentEvent(PaymentFailed("declined"))
        val command = saga.onPaymentFailed(event, context("OrderPaymentSaga.onPaymentFailed", event))!!
        command.ref.assert().isEqualTo(order)
        command.body.assert().isEqualTo(CancelOrder("The payment failed: declined"))
        saga.onPaymentFailed(paymentEvent(PaymentSucceeded("t")), context("OrderPaymentSaga.onPaymentFailed", event))
            .assert().isNull()
    }

    @Test
    fun `an order that refused its payment should have the payment refunded`(): Unit = runTest {
        val handler = ModelProvider.load().single().processors(Dependencies.of(SimulatedPaymentGateway(TOTAL)))
            .single { it.name == "OrderPaymentSaga.onPaymentSucceeded" }
        val onRejected = handler.rejectionHandlerOf(PayOrder::class.java)!!
        onRejected.name.assert().isEqualTo("OrderPaymentSaga.onPayRejected")
        val rejected = CommandRejected<PayOrder>(
            commandId = "derived-2",
            commandType = "eshop-ordering.order.PayOrder",
            ref = order,
            error = ErrorInfo("eshop-ordering.order.wrong-status", "The order alice-1 is CANCELLED."),
            causation = Causation("command-2", "eshop-payment.payment/$TENANT/$ORDER@2"),
            executionId = ExecutionId("OrderPaymentSaga.onPaymentSucceeded", payment, 2),
        )
        val refund = onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).single()
        refund.ref.assert().isEqualTo(payment)
        refund.body.assert()
            .isEqualTo(RefundPayment("The order alice-1 refused the payment: The order alice-1 is CANCELLED."))
    }

    private companion object {
        const val TENANT = "tenant"
        const val ORDER = "alice-1"
        val TOTAL = BigDecimal("29.00")
    }
}
