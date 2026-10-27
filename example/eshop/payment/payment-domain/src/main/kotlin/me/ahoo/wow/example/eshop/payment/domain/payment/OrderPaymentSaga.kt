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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import java.math.BigDecimal

/**
 * The payment of an order between the ordering and the payment contexts, after the classic eShop's integration
 * events (`OrderStatusChangedToStockConfirmed`, `OrderPaymentSucceeded`, `OrderPaymentFailed`):
 *
 * - [onOrderStockConfirmed]: an order whose stock the ordering context confirmed requests its payment. The saga reads
 *   the order stream the ordering context publishes, `wow.eshop-ordering.order.state`, and takes each
 *   `OrderStockConfirmed` of it, as the ordering api's class: the class names the stream, as the ordering's contract
 *   lists the event under its order.
 * - [onPaymentSucceeded]: a payment the gateway approved pays its order: `PayOrder` goes to the ordering context's
 *   command topic, `wow.eshop-ordering.commands`.
 * - [onPaymentFailed]: a payment the gateway declined cancels its order; the cancelled order releases its stock, as
 *   any cancelled order does (the ordering's order-process saga).
 * - [onPayRejected]: an order that refuses its payment (it was cancelled meanwhile, its grace period passed, say)
 *   comes back as a receipt on `wow.eshop-payment.receipts`, and the payment is refunded.
 *
 * A `CancelOrder` the order rejects has no handler: only a paid order rejects it, and a failed payment paid nothing,
 * so such a rejection parks the payment for [onPaymentFailed], to be looked at.
 *
 * Each handler names only its target's id: the contract the ordering api's jar carries assigns
 * `PayOrder` and `CancelOrder` to the ordering's order, the payment's own Manifest assigns `RequestPayment` and
 * `RefundPayment` to the payment, and each command is in the tenant of the commit or receipt it answers.
 */
@Saga
class OrderPaymentSaga {
    // region peer-stream
    @OnEvent
    fun onOrderStockConfirmed(
        event: StateTypedEvent<OrderView, OrderStockConfirmed>,
        context: SagaContext,
    ): Command<RequestPayment> = context.command(event.ref.id, RequestPayment(event.ref.id, event.state.totalAmount))
    // endregion

    // region peer-command
    @OnEvent
    fun onPaymentSucceeded(
        event: StateTypedEvent<PaymentState, PaymentSucceeded>,
        context: SagaContext,
    ): Command<PayOrder> = context.command(event.state.orderId, PayOrder(event.ref.id, event.state.amount))
    // endregion

    @OnEvent
    fun onPaymentFailed(
        event: StateTypedEvent<PaymentState, PaymentFailed>,
        context: SagaContext
    ): Command<CancelOrder> =
        context.command(event.state.orderId, CancelOrder("The payment failed: ${event.event.reason}"))

    // region peer-rejected
    /** An order that refuses its payment has the payment refunded. */
    @OnRejected
    fun onPayRejected(rejected: CommandRejected<PayOrder>, context: SagaContext): Command<RefundPayment> =
        context.command(
            rejected.executionId.ref.id,
            RefundPayment("The order ${rejected.ref.id} refused the payment: ${rejected.error.message}"),
        )
    // endregion
}

// region order-view
/**
 * The payment context's view of the ordering's order, decoded from the state the ordering context publishes: what
 * the order costs. Reading ignores the rest.
 */
data class OrderView(val totalAmount: BigDecimal = BigDecimal.ZERO)
// endregion
