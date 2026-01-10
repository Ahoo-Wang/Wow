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

package me.ahoo.wow.example.domain.coroutine

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.CommandResultAccessor
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.domain.order.CreateOrderSpec
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.id.GlobalIdGenerator

@Suppress("UnusedPrivateProperty")
@AggregateRoot
class CoroutineOrder(private val state: CoroutineOrderState) {

    suspend fun onCommand(
        command: CommandMessage<CreateOrder>,
        @Name("createOrderSpec") specification: CreateOrderSpec,
        commandResultAccessor: CommandResultAccessor
    ): OrderCreated {
        val createOrder = command.body
        require(createOrder.items.isNotEmpty()) {
            "items can not be empty."
        }
        createOrder.items.asFlow().collect {
            specification.require(it).awaitSingle()
        }
        val orderCreated = OrderCreated(
            orderId = command.aggregateId.id,
            items = createOrder.items.map {
                OrderItem(
                    id = GlobalIdGenerator.generateAsString(),
                    productId = it.productId,
                    price = it.price,
                    quantity = it.quantity,
                )
            },
            address = createOrder.address,
            fromCart = createOrder.fromCart,
        )
        commandResultAccessor.setCommandResult(
            OrderState::totalAmount.name,
            orderCreated.items.sumOf { it.totalPrice }
        )
        return orderCreated
    }
}
