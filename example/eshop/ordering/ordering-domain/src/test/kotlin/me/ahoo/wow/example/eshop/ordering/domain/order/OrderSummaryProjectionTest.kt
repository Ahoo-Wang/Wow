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
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.test.projectionSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The order-summary projection writes each order's line of its buyer's history, the newest version kept. */
class OrderSummaryProjectionTest {
    private val saved = mutableListOf<Pair<OrderSummary, Int>>()
    private val handlers = orderSummaryProjectionHandlers(
        Dependencies.of(OrderSummaries { summary, version -> saved += summary to version }),
    )
    private val order = EshopOrderingContract.Order.ref("tenant", "alice-1")
    private val lines = listOf(OrderItem("mug", BigDecimal("8.50"), 2), OrderItem("cap", BigDecimal("12.00"), 1))
    private val state = OrderState().apply {
        onSourcing(OrderSubmitted(lines))
        onSourcing(OrderAwaitingValidation)
    }
    private val event = stateEvent(order, state, OrderAwaitingValidation, version = 2, scope = Scope("alice"))

    @Test
    fun `a commit should save the order's summary at its version`() = projectionSpec(handlers) {
        whenEvent(event) {
            expectHandledBy("onEvent")
            expectNoError()
        }
        saved.single().assert().isEqualTo(
            OrderSummary(
                tenantId = "tenant",
                orderId = "alice-1",
                buyerId = "alice",
                status = OrderStatus.AWAITING_VALIDATION,
                totalAmount = BigDecimal("29.00"),
                units = 3,
                updatedAt = event.commitTime,
            ) to 2,
        )
    }

    @Test
    fun `the same commit handled again should save the same summary`() = projectionSpec(handlers) {
        whenEvent(event) { expectNoError() }
        whenEvent(event) { expectNoError() }
        saved.distinct().size.assert().isEqualTo(1)
    }
}
