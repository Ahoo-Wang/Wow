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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.cocache.api.StateViewCache
import me.ahoo.wow.cocache.api.StateViewUnavailableException
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.test.DEFAULT_TENANT
import me.ahoo.wow.test.ProcessorExpectations
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.sagaSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * The gateway saga over the simulated gateway, which approves up to its limit and declines above it, and the
 * ordering's orders as its shared cache holds them.
 */
class PaymentGatewaySagaTest {
    /** The ordering's shared cache, as the ordering service writes it: the order [ORDER] of two mugs. */
    private val orders = object : StateViewCache<OrderStateView> {
        override val name: String = "wow.eshop-ordering.order"

        override suspend fun get(key: StateKey): OrderStateView? = when (key.id) {
            ORDER -> OrderStateView(
                confirmedProducts = setOf("mug"),
                items = listOf(OrderItem("mug", BigDecimal("50.00"), 2)),
                status = OrderStatus.STOCK_CONFIRMED,
                totalAmount = BigDecimal("100.01"),
            )
            // The cache does not hold it, and the ordering service cannot be read.
            else -> throw StateViewUnavailableException(listOf(key), "The ordering service is unreachable.")
        }

        override suspend fun getAll(keys: Collection<StateKey>): Map<StateKey, OrderStateView> =
            keys.mapNotNull { key -> get(key)?.let { key to it } }.toMap()
    }

    /** The saga under test, with the services it takes. */
    private val handlers = paymentGatewaySagaHandlers(
        gateway = SimulatedPaymentGateway(BigDecimal("100.00")),
        orders = orders,
    )

    /** The payment of the order, requested for [amount]. */
    private fun ProcessorSpec.requested(amount: String, then: ProcessorExpectations.() -> Unit) {
        givenAggregate(paymentModel(), ORDER)
        whenEvent(PaymentRequested(ORDER, BigDecimal(amount)), by = RequestPayment::class, expect = then)
    }

    @Test
    fun `an amount within the limit should confirm the payment under the gateway's transaction`() = sagaSpec(handlers) {
        requested("100.00") {
            // The charge's key is the call's: the execution and the PaymentRequested's index (decision D264).
            val payment = EshopPaymentContract.Payment.ref(DEFAULT_TENANT, ORDER)
            expectCommand(ConfirmPayment("simulated-${ExecutionId(HANDLER, payment, 1)}:0"), to = payment)
            // Handling the commit again charges with the same key, and derives the same request.
            expectDeterministic()
        }
    }

    @Test
    fun `an amount over the limit should fail the payment`() = sagaSpec(handlers) {
        requested("100.01") {
            expectCommandEnvelope<FailPayment> {
                ref.assert().isEqualTo(EshopPaymentContract.Payment.ref(DEFAULT_TENANT, ORDER))
                // The charge says what it is for: the order's items, read from the ordering's cache.
                body.reason.assert().contains("declined 100.01").contains("(2 × mug)").contains("limit of 100.00")
            }
        }
    }

    @Test
    fun `an order the cache cannot serve should be charged by its id`() = sagaSpec(handlers) {
        givenAggregate(paymentModel(), OTHER)
        whenEvent(PaymentRequested(OTHER, BigDecimal("200.00")), by = RequestPayment::class) {
            expectCommand<FailPayment> { reason.assert().contains("(order $OTHER)") }
        }
    }

    @Test
    fun `a gateway that is down should be retried, then park the payment`() = sagaSpec(
        paymentGatewaySagaHandlers(gateway = SimulatedPaymentGateway(BigDecimal("100.00")) { true }, orders = orders),
    ) {
        requested("10.00") {
            // A technical failure, not a decline: the runtime's budget retries it before it parks.
            expectRetried().message.assert().contains("unreachable")
            expectParked(attempt = HandlerRetry.DEFAULT_MAX_ATTEMPTS)
        }
    }

    @Test
    fun `another event of the payment should charge nothing`() = sagaSpec(handlers) {
        givenAggregate(paymentModel(), ORDER, PaymentRequested(ORDER, BigDecimal("10.00")))
        whenEvent(PaymentSucceeded("transaction-1"), by = ConfirmPayment::class) { expectNoCommand() }
    }

    @Test
    fun `a gateway's limit should not be negative`() {
        assertThrows<IllegalArgumentException> { SimulatedPaymentGateway(BigDecimal("-1")) }
    }

    private companion object {
        const val ORDER = "alice-1"
        const val OTHER = "bob-1"
        const val HANDLER = "PaymentGatewaySaga.onPaymentRequested"
    }
}
