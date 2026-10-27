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
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderPaid
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.reactorSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

/** The shipment notifier tells a shipped order's buyer, once per commit, within its own retry budget. */
class ShipmentNotifierTest {
    private val sent = mutableListOf<Triple<String, String, String>>()
    private val failures = ArrayDeque<Throwable>()
    private val handlers = shipmentNotifierHandlers(
        Dependencies.of(
            BuyerNotifications { buyer, order, key ->
                failures.removeFirstOrNull()?.let { throw it }
                sent += Triple(buyer, order, key)
            },
        ),
    )
    private val model = orderModel(paymentPolicy = PaymentPolicy(30.minutes))

    /** Alice's order, submitted, then paid for: its shipment is its third commit. */
    private fun ProcessorSpec.paid(scope: Scope = Scope("alice")) = givenAggregate(
        model,
        "alice-1",
        OrderSubmitted(listOf(OrderItem("mug", BigDecimal.ONE, 2))),
        OrderPaid("payment-1", BigDecimal("2")),
        scope = scope,
    )

    @Test
    fun `a shipped order should notify its buyer with the commit's key`() = reactorSpec(handlers) {
        val order = paid()
        whenEvent(OrderShipped, by = ShipOrder::class) {
            expectHandledBy("onOrderShipped")
            expectNoError()
        }
        sent.single().assert().isEqualTo(
            Triple("alice", "alice-1", "ShipmentNotifier.onOrderShipped:${order.ref}@3:0"),
        )
    }

    @Test
    fun `an order without a buyer should notify no one`() = reactorSpec(handlers) {
        paid(Scope.NONE)
        whenEvent(OrderShipped, by = ShipOrder::class) { expectNoError() }
        sent.assert().isEmpty()
    }

    // region test
    @Test
    fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
        val order = paid()
        failures += IllegalStateException("The mail service is down.")
        whenEvent(OrderShipped, by = ShipOrder::class) {
            // Within the notifier's own budget, the runtime asks again.
            expectRetried().assert().isInstanceOf(IllegalStateException::class.java)
        }
        whenEvent(OrderShipped, by = ShipOrder::class) { expectNoError() }
        sent.single().third.assert().isEqualTo("ShipmentNotifier.onOrderShipped:${order.ref}@3:0")
    }
    // endregion

    @Test
    fun `a notification failing past the notifier's budget should park`() = reactorSpec(handlers) {
        paid()
        failures += IllegalStateException("The mail service is down.")
        whenEvent(OrderShipped, by = ShipOrder::class) {
            expectRetried(attempt = 4)
            expectParked(attempt = 5)
        }
    }

    @Test
    fun `an unknown buyer should park at once`() = reactorSpec(handlers) {
        paid()
        failures += UnknownBuyerException("alice")
        whenEvent(OrderShipped, by = ShipOrder::class) {
            // The notifier's @Retry takes it for unrecoverable: no attempt again would find the buyer.
            val unknown = expectParked() as UnknownBuyerException
            unknown.buyerId.assert().isEqualTo("alice")
        }
    }
}
