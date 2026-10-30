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
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.annotation.Sends
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.cocache.api.StateViewCache
import me.ahoo.wow.cocache.api.StateViewUnavailableException
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested

/**
 * Charges the [gateway] for each requested payment and records its answer on the payment: [ConfirmPayment] when it
 * approves, [FailPayment] when it declines.
 *
 * The classic eShop's `PaymentProcessor` is an event handler that calls the gateway; here it is a saga, since a
 * processor that sends commands is a saga, and a reactor cannot send one. The charge is the saga's outside call: its
 * idempotency key is the call's `invocationKey`, the same each time the same commit is handled, so a
 * handling retried after a failure does not charge twice. A gateway that fails is retried, then the payment is parked for this saga.
 *
 * The charge says what it is for, the order's items, which the saga reads from the ordering context's shared cache of
 * its orders ([orders], `StateViewCache<OrderStateView>`, wow-cocache): the ordering service writes each order into it
 * after its commits, and a miss reads the ordering service. That is a trusted read for the saga's own use, the
 * charge's description: the order is never decided by it, and an order the cache cannot give is charged by its id.
 * The saga keeps no state: the cache says when it cannot serve, once per outage.
 */
@Saga
class PaymentGatewaySaga(
    private val gateway: PaymentGateway,
    private val orders: StateViewCache<OrderStateView>,
) {
    @OnEvent
    @Sends(derives = [ConfirmPayment::class, FailPayment::class])
    suspend fun onPaymentRequested(
        event: StateTypedEvent<PaymentState, PaymentRequested>,
        context: SagaContext,
    ): CommandRequest<*> {
        val requested = event.event
        val orderId = event.state.orderId
        val description = orderOf(event.ref.tenantId, orderId)?.let(::describe) ?: "order $orderId"
        return when (val outcome = gateway.charge(event.ref.id, requested.amount, description, context.invocationKey)) {
            is GatewayOutcome.Approved -> CommandRequest.of(ConfirmPayment(outcome.transactionId), event.ref.id)
            is GatewayOutcome.Declined -> CommandRequest.of(FailPayment(outcome.reason), event.ref.id)
        }
    }

    // region read-order
    /**
     * The order [orderId] as the ordering's shared cache gives it; null when the cache cannot serve it now (the
     * ordering does not share its orders here, or a miss cannot reach the ordering service). The charge's description
     * is the saga's own use of the order, so a payment never waits on the ordering for it. Only an unavailable order
     * falls back, which the cache itself logs: an order that does not decode, or a defect, fails the handling as any
     * error does.
     */
    private suspend fun orderOf(tenantId: String, orderId: String): OrderStateView? = try {
        orders.get(tenantId, orderId)
    } catch (_: StateViewUnavailableException) {
        null
    }
    // endregion

    /** What the charge is for, as the buyer's statement shows it: `2 × mug, 1 × cap`. */
    private fun describe(order: OrderStateView): String =
        order.items.joinToString { "${it.quantity} × ${it.productId}" }
}
