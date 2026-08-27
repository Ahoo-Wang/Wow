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
@file:Suppress("unused")

package me.ahoo.wow.example.domain.order

import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.merge
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.api.order.OrderReceived
import me.ahoo.wow.example.api.order.OrderShipped
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.state.StateAggregateTagsExtractor
import me.ahoo.wow.models.common.StatusCapable
import java.math.BigDecimal

/**
 * Event-sourced state for [Order].
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.state.StateAggregate
 */
@Schema(name = "WowExampleOrderState")
class OrderState(
    /**
     * The conventional `id` name makes the aggregate-ID annotation optional.
     */
    val id: String
) : StatusCapable<OrderStatus>, StateAggregateTagsExtractor<OrderState> {

    /**
     * unmodifiable.
     */
    lateinit var items: List<OrderItem>
        private set
    lateinit var address: ShippingAddress
        private set
    var totalAmount: BigDecimal = BigDecimal.ZERO
        private set
    var paidAmount: BigDecimal = BigDecimal.ZERO
        private set
    override var status = OrderStatus.CREATED
        private set

    /**
     * The amount that remains payable.
     */
    val payable: BigDecimal
        get() {
            return totalAmount.minus(paidAmount)
        }

    /**
     * Applies the initial event without external dependencies.
     * The conventional `onSourcing` name makes the sourcing annotation optional.
     */
    fun onSourcing(orderCreated: OrderCreated) {
        address = orderCreated.address
        items = orderCreated.items
        totalAmount = orderCreated
            .items
            .map { it.totalPrice }
            .reduce { totalPrice, moneyToAdd -> totalPrice + moneyToAdd }
        status = OrderStatus.CREATED
    }

    fun onSourcing(addressChanged: AddressChanged) {
        address = addressChanged.shippingAddress
    }

    private fun onSourcing(orderPaid: OrderPaid) {
        paidAmount = paidAmount.plus(orderPaid.amount)
        if (orderPaid.paid) {
            status = OrderStatus.PAID
        }
    }

    fun onSourcing(orderShipped: OrderShipped) {
        status = OrderStatus.SHIPPED
    }

    fun onSourcing(orderReceived: OrderReceived) {
        status = OrderStatus.RECEIVED
    }

    override fun extract(source: ReadOnlyStateAggregate<OrderState>): AbacTags {
        val tags = mapOf(
            "address-country" to listOf(address.country),
            "address-province" to listOf(address.province),
        )
        return tags.merge(source.tags)
    }
}
