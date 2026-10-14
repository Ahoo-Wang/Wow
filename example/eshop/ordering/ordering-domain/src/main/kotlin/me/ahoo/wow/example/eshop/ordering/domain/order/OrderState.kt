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

import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderPaid
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockItemConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import java.math.BigDecimal

/**
 * The state of an [Order], changed in place by its events. Every property has a value before the order is submitted:
 * the kernel serializes the state as it is.
 */
class OrderState(val id: String) {
    var items: List<OrderItem> = emptyList()
        private set

    var totalAmount: BigDecimal = BigDecimal.ZERO
        private set

    var status: OrderStatus = OrderStatus.SUBMITTED
        private set

    /** The products the inventory reserved so far. */
    var confirmedProducts: Set<String> = emptySet()
        private set

    var paymentId: String? = null
        private set

    /** Why the order was cancelled; null while it is not. */
    var cancelReason: String? = null
        private set

    /** The order's products, each once, in the order of their first line. */
    fun products(): Set<String> = items.mapTo(linkedSetOf()) { it.productId }

    /** The order's quantity of each product, its lines of one product added up. */
    fun quantities(): Map<String, Int> = items.groupingBy { it.productId }.fold(
        0
    ) { total, item -> total + item.quantity }

    fun onSourcing(event: OrderSubmitted) {
        items = event.items
        totalAmount = event.items.fold(BigDecimal.ZERO) { total, item ->
            total + item.unitPrice.multiply(BigDecimal(item.quantity))
        }
        status = OrderStatus.SUBMITTED
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderAwaitingValidation) {
        status = OrderStatus.AWAITING_VALIDATION
    }

    fun onSourcing(event: OrderStockItemConfirmed) {
        confirmedProducts = confirmedProducts + event.productId
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderStockConfirmed) {
        status = OrderStatus.STOCK_CONFIRMED
    }

    fun onSourcing(event: OrderPaid) {
        paymentId = event.paymentId
        status = OrderStatus.PAID
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderShipped) {
        status = OrderStatus.SHIPPED
    }

    fun onSourcing(event: OrderCancelled) {
        cancelReason = event.reason
        status = OrderStatus.CANCELLED
    }
}
