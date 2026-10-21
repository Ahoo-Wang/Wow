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

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Projection
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import java.math.BigDecimal

/**
 * One line of a buyer's order history: an order as its buyer lists them, by the time it last changed. The
 * [OrderSummaryProjection] writes it from the order's commits; the history page reads it from the store the
 * [OrderSummaries] are kept in, which holds no event and no state of the aggregate.
 */
data class OrderSummary(
    val tenantId: String,
    val orderId: String,
    /** The order's buyer: its owner, null for an order without one. */
    val buyerId: String?,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    /** The units the order holds, all its lines added up. */
    val units: Int,
    /** When the order last changed, in epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * Where the order summaries are kept: a table, a document collection, a search index. A port of the
 * [OrderSummaryProjection], which the server binds to its store and a test fakes.
 */
fun interface OrderSummaries {
    /**
     * Saves [summary] as the order's summary at [version], unless the store already holds one of that version or a
     * later one: a commit may be handled more than once (a retry, a replay, a lease handover), and an older commit must
     * never overwrite a newer one.
     */
    fun save(summary: OrderSummary, version: Int)
}

/**
 * Keeps each order's [OrderSummary] in [OrderSummaries] after every commit of the order, from the state after it. A
 * projection starts at the beginning of the log, so a service that adds it fills the history of the orders submitted
 * before. Its store is outside Wow, so its write blocks a virtual thread rather than the shared workers.
 */
@Projection
class OrderSummaryProjection(private val summaries: OrderSummaries) {
    // region project
    @Blocking
    @OnEvent
    fun onEvent(event: StateEvent<OrderState>) {
        val state = event.state
        summaries.save(
            OrderSummary(
                tenantId = event.ref.tenantId,
                orderId = event.ref.id,
                buyerId = event.scope.ownerId,
                status = state.status,
                totalAmount = state.totalAmount,
                units = state.quantities().values.sum(),
                updatedAt = event.commitTime,
            ),
            version = event.version,
        )
    }
    // endregion
}
