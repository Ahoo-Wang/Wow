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
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.test.reactorSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test

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
    private val order = EshopOrderingContract.Order.ref("tenant", "alice-1")

    private fun shipped(scope: Scope = Scope("alice")) =
        stateEvent(order, OrderState(), OrderShipped, version = 6, scope = scope)

    @Test
    fun `a shipped order should notify its buyer with the commit's key`() = reactorSpec(handlers) {
        whenEvent(shipped()) {
            expectHandledBy("onOrderShipped")
            expectNoError()
        }
        sent.single().assert().isEqualTo(
            Triple("alice", "alice-1", "ShipmentNotifier.onOrderShipped:$order@6:0"),
        )
    }

    @Test
    fun `an order without a buyer should notify no one`() = reactorSpec(handlers) {
        whenEvent(shipped(Scope.NONE)) { expectNoError() }
        sent.assert().isEmpty()
    }

    // region test
    @Test
    fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
        failures += IllegalStateException("The mail service is down.")
        whenEvent(shipped()) { expectError<IllegalStateException>() }
        whenEvent(shipped()) { expectNoError() }
        sent.single().third.assert().isEqualTo("ShipmentNotifier.onOrderShipped:$order@6:0")
    }
    // endregion

    @Test
    fun `an unknown buyer should fail the handler`() = reactorSpec(handlers) {
        failures += UnknownBuyerException("alice")
        whenEvent(shipped()) {
            expectError<UnknownBuyerException> { buyerId.assert().isEqualTo("alice") }
        }
    }

    @Test
    fun `the notifier should carry its own retry budget`() {
        handlers.single().retry.assert().isEqualTo(
            HandlerRetry.of(
                maxAttempts = 5,
                initialBackoff = "1s",
                maxBackoff = "30s",
                timeout = "10s",
                recoverable = emptyList(),
                unrecoverable = listOf(UnknownBuyerException::class.java),
            ),
        )
    }
}
