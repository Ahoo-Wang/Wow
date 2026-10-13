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
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.kernel.ExecutionSagaContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** The gateway saga over the simulated gateway, which approves up to its limit and declines above it. */
class PaymentGatewaySagaTest {
    private val payment = AggregateType("eshop-payment", "payment").ref(TENANT, ORDER)
    private val gateway = SimulatedPaymentGateway(BigDecimal("100.00"))
    private val saga = PaymentGatewaySaga(gateway)

    private fun requested(amount: String): StateEvent<PaymentState> {
        val event = PaymentRequested(ORDER, BigDecimal(amount))
        val state = PaymentState(ORDER).apply { onSourcing(event) }
        return StateEvent(
            payment,
            1,
            "command-1",
            "request-1",
            "eshop-payment.payment.RequestPayment",
            1L,
            listOf(event),
            state
        )
    }

    private fun context(event: StateEvent<PaymentState>) =
        ExecutionSagaContext(ExecutionId(HANDLER, event.ref, event.version), event)

    @Test
    fun `an amount within the limit should confirm the payment under the gateway's transaction`(): Unit = runTest {
        val event = requested("100.00")
        val command = saga.onPaymentRequested(event, context(event))!!
        command.ref.assert().isEqualTo(payment)
        command.body.assert().isEqualTo(ConfirmPayment("simulated-${ExecutionId(HANDLER, payment, 1)}"))
        // Handling the commit again charges with the same key, and derives the same request.
        saga.onPaymentRequested(event, context(event))!!.requestId.assert().isEqualTo(command.requestId)
    }

    @Test
    fun `an amount over the limit should fail the payment`(): Unit = runTest {
        val event = requested("100.01")
        val command = saga.onPaymentRequested(event, context(event))!!
        command.ref.assert().isEqualTo(payment)
        (command.body as FailPayment).reason.assert().contains("declined 100.01").contains("limit of 100.00")
    }

    @Test
    fun `another event of the payment should charge nothing`(): Unit = runTest {
        val event = requested("10.00").copy(events = listOf(PaymentSucceeded("transaction-1")))
        saga.onPaymentRequested(event, context(event)).assert().isNull()
    }

    @Test
    fun `a gateway's limit should not be negative`() {
        assertThrows<IllegalArgumentException> { SimulatedPaymentGateway(BigDecimal("-1")) }
    }

    private companion object {
        const val TENANT = "tenant"
        const val ORDER = "alice-1"
        const val HANDLER = "PaymentGatewaySaga.onPaymentRequested"
    }
}
