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

package me.ahoo.wow.example.domain.coroutine

import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.models.common.StatusCapable
import java.math.BigDecimal

/**
 * 订单聚合状态 .
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.state.StateAggregate
 */
class CoroutineOrderState(
    /**
     * [me.ahoo.wow.api.annotation.AggregateId] 注解是可选的，约定默认使用字段名为 `id` 为聚合ID.
     */
    val id: String
) : StatusCapable<OrderStatus> {
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
     * 订单剩余应付金额.
     */
    val payable: BigDecimal
        get() {
            return totalAmount.minus(paidAmount)
        }

    fun onSourcing(orderCreated: OrderCreated) {
        address = orderCreated.address
        items = orderCreated.items
        totalAmount = orderCreated
            .items
            .map { it.totalPrice }
            .reduce { totalPrice, moneyToAdd -> totalPrice + moneyToAdd }
        status = OrderStatus.CREATED
    }
}
