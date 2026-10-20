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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested

/**
 * Charges the [gateway] for each requested payment and records its answer on the payment: [ConfirmPayment] when it
 * approves, [FailPayment] when it declines.
 *
 * The classic eShop's `PaymentProcessor` is an event handler that calls the gateway; here it is a saga, since a
 * processor that sends commands is a saga, and a reactor cannot send one. The charge is the saga's outside call: its
 * idempotency key is the call's `invocationKey` (decision D264), the same each time the same commit is handled, so a
 * handling retried after a failure does not charge twice. A gateway that fails is retried, then the payment is parked for this saga.
 */
@Saga
class PaymentGatewaySaga(private val gateway: PaymentGateway) {
    @OnEvent(derives = [ConfirmPayment::class, FailPayment::class])
    suspend fun onPaymentRequested(
        event: StateTypedEvent<PaymentState, PaymentRequested>,
        context: SagaContext,
    ): Command<*> {
        val requested = event.event
        return when (val outcome = gateway.charge(event.ref.id, requested.amount, context.invocationKey)) {
            is GatewayOutcome.Approved -> context.command(event.ref, ConfirmPayment(outcome.transactionId))
            is GatewayOutcome.Declined -> context.command(event.ref, FailPayment(outcome.reason))
        }
    }
}
