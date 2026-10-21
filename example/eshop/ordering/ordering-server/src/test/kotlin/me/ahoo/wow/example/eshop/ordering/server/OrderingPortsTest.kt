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

package me.ahoo.wow.example.eshop.ordering.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.domain.order.OrderSummary
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The service's own bindings of the order processors' ports. */
class OrderingPortsTest {
    private fun summary(status: OrderStatus) =
        OrderSummary("tenant", "alice-1", "alice", status, BigDecimal("29.00"), 3, updatedAt = 1)

    @Test
    fun `the summaries should keep each order's newest version`() {
        val summaries = InMemoryOrderSummaries()
        summaries.save(summary(OrderStatus.PAID), version = 5)
        summaries.save(summary(OrderStatus.SUBMITTED), version = 1)
        summaries.summaryOf("tenant", "alice-1")?.status.assert().isEqualTo(OrderStatus.PAID)
        summaries.save(summary(OrderStatus.SHIPPED), version = 6)
        summaries.summaryOf("tenant", "alice-1")?.status.assert().isEqualTo(OrderStatus.SHIPPED)
        summaries.summaryOf("tenant", "bob-1").assert().isNull()
    }

    @Test
    fun `the logged notifications should accept a shipped order`(): Unit = runBlocking {
        LoggedBuyerNotifications().orderShipped("alice", "alice-1", key = "ShipmentNotifier.onOrderShipped:x@6:0")
    }
}
