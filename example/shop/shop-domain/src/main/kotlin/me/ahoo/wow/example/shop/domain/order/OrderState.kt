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

package me.ahoo.wow.example.shop.domain.order

import me.ahoo.wow.api.annotation.Upgrade
import me.ahoo.wow.example.shop.api.order.AddressChanged
import me.ahoo.wow.example.shop.api.order.OrderCancelled
import me.ahoo.wow.example.shop.api.order.OrderCreated
import me.ahoo.wow.example.shop.api.order.OrderItem
import me.ahoo.wow.example.shop.api.order.OrderOverPaid
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.example.shop.api.order.OrderPayDuplicated
import me.ahoo.wow.example.shop.api.order.OrderReceived
import me.ahoo.wow.example.shop.api.order.OrderShipped
import me.ahoo.wow.example.shop.api.order.OrderStatus
import me.ahoo.wow.example.shop.api.order.ShippingAddress
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * The order's state, changed in place by its events. Every property has a value before the order is placed, since
 * the kernel serializes the state as it is, for snapshots and for the test DSL's purity check.
 */
class OrderState {
    var items: List<OrderItem> = emptyList()
        private set
    var address: ShippingAddress? = null
        private set
    var totalAmount: BigDecimal = BigDecimal.ZERO
        private set
    var paidAmount: BigDecimal = BigDecimal.ZERO
        private set
    var status: OrderStatus = OrderStatus.CREATED
        private set

    /** Why the order was cancelled; null while it is not. */
    var cancelReason: String? = null
        private set

    fun onSourcing(event: OrderCreated) {
        items = event.items
        address = event.address
        totalAmount = event.totalAmount()
        status = OrderStatus.CREATED
    }

    fun onSourcing(event: AddressChanged) {
        address = event.shippingAddress
    }

    fun onSourcing(event: OrderPaid) {
        paidAmount += event.amount
        if (event.paid) {
            status = OrderStatus.PAID
        }
    }

    /*
     * The payment service refunds the excess of an over-payment and a payment the order could not take; the order's
     * state does not change. V10 still needs a sourcing function for each, since a model evolves every event its
     * commands produce.
     */

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderOverPaid) = Unit

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderPayDuplicated) = Unit

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderShipped) {
        status = OrderStatus.SHIPPED
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderReceived) {
        status = OrderStatus.RECEIVED
    }

    fun onSourcing(event: OrderCancelled) {
        cancelReason = event.reason
        status = OrderStatus.CANCELLED
    }
}

/**
 * Upgrades an [OrderCreated] of revision 1, as 9.x wrote it, to revision 2 (decision D82): the order's id leaves the
 * event, being the stream's aggregate id (decision D68), and so does each item's `totalPrice`, which revision 2
 * computes.
 */
@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode {
    node.remove("orderId")
    node.get("items")?.forEach { item -> (item as? ObjectNode)?.remove("totalPrice") }
    return node
}
