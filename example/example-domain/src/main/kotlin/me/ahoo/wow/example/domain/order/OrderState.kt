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
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.api.order.OrderReceived
import me.ahoo.wow.example.api.order.OrderShipped
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.api.order.ShippingAddress
import java.math.BigDecimal

/**
 * 订单聚合状态 .
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.state.StateAggregate
 */
@Schema(name = "WowExampleOrderState")
class OrderState(
    /**
     * [me.ahoo.wow.api.annotation.AggregateId] 注解是可选的，约定默认使用字段名为 `id` 为聚合ID.
     */
    val id: String
) {

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
    var status = OrderStatus.CREATED
        private set

    /**
     * 订单剩余应付金额.
     */
    val payable: BigDecimal
        get() {
            return totalAmount.minus(paidAmount)
        }

    /**
     * 事件概念：既定事实，已发生的事实.(不可篡改)
     * <pre>
     * 事件朔源处理函数职责/概念.
     * 1.修改聚合状态（并且有且只有这一种方式）
     </pre> *
     * 因为事件朔源处理函数只负责将聚合状态执行变更，所以一般只需同步处理，返回值为 void .
     * 并且事件朔源函数不对外部环境/服务产生依赖。
     *
     *
     * [me.ahoo.wow.api.annotation.OnSourcing] 注解是可选的，约定默认使用方法名 `onSourcing` .
     *
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
}
