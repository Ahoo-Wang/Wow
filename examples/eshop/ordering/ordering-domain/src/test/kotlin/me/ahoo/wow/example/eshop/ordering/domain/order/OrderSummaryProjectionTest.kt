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
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.test.DEFAULT_START
import me.ahoo.wow.test.DEFAULT_TENANT
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.projectionSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

/** The order-summary projection writes each order's line of its buyer's history, the newest version kept. */
class OrderSummaryProjectionTest {
    private val saved = mutableListOf<Pair<OrderSummary, Int>>()
    private val handlers = orderSummaryProjectionHandlers(
        summaries = OrderSummaries { summary, version -> saved += summary to version },
    )
    private val model = orderModel(paymentPolicy = PaymentPolicy(30.minutes))
    private val lines = listOf(OrderItem("mug", BigDecimal("8.50"), 2), OrderItem("cap", BigDecimal("12.00"), 1))

    /** Alice's order, submitted with [lines]. */
    private fun ProcessorSpec.submitted() = givenAggregate(
        model,
        "alice-1",
        OrderSubmitted(lines),
        scope = Scope("alice")
    )

    @Test
    fun `a commit should save the order's summary at its version`() = projectionSpec(handlers) {
        submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) { expectHandledBy("onEvent") }
        saved.single().assert().isEqualTo(
            OrderSummary(
                tenantId = DEFAULT_TENANT,
                orderId = "alice-1",
                buyerId = "alice",
                status = OrderStatus.AWAITING_VALIDATION,
                totalAmount = BigDecimal("29.00"),
                units = 3,
                updatedAt = DEFAULT_START.toEpochMilli(),
            ) to 2,
        )
    }

    // region test
    @Test
    fun `the same commit handled again should save the same summary`() = projectionSpec(handlers) {
        submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class)
        // A subscription hands a commit at least once: the same commit again saves the same summary, at its version.
        whenRedelivered()
        saved.distinct().size.assert().isEqualTo(1)
    }
    // endregion

    @Test
    fun `an older commit delivered late should be saved at its own version`() = projectionSpec(handlers) {
        submitted()
        whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class)
        whenEvent(OrderCancelled("The customer changed their mind."), by = CancelOrder::class)
        // Version 2 arrives after version 3: the summary says its version, and the summaries keep the newest one.
        whenDeliveredLate(2)
        saved.map { it.second }.assert().containsExactly(2, 3, 2)
        saved.last().first.status.assert().isEqualTo(OrderStatus.AWAITING_VALIDATION)
    }
}
