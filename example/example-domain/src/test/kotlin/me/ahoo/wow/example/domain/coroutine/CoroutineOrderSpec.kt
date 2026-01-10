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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec
import me.ahoo.wow.example.domain.order.OrderFixture
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.test.AggregateSpec
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

class CoroutineOrderSpec : AggregateSpec<CoroutineOrder, CoroutineOrderState>({
    on {
        val ownerId = generateGlobalId()
        val orderItem =
            CreateOrder.Item(
                productId = generateGlobalId(),
                price = BigDecimal.valueOf(10),
                quantity = 10,
            )
        val orderItems = listOf(orderItem)

        givenOwnerId(ownerId)
        // Mock services inline
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.quantity }
                    .first()
                    .toMono()
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String) =
                orderItems
                    .filter { it.productId == productId }
                    .map { it.price }
                    .first()
                    .toMono()
        }

        inject {
            register(DefaultCreateOrderSpec(inventoryService, pricingService))
        }

        val totalAmount = orderItem.price.multiply(BigDecimal.valueOf(orderItem.quantity.toLong()))

        whenCommand(CreateOrder(orderItems, OrderFixture.SHIPPING_ADDRESS, false)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectStateAggregate {
                aggregateId.tenantId.assert().isNotNull()
                ownerId.assert().isEqualTo(ownerId)
            }
            expectState {
                id.assert().isNotNull()
                address.assert().isEqualTo(OrderFixture.SHIPPING_ADDRESS)
                items.assert().hasSize(1)
                status.assert().isEqualTo(OrderStatus.CREATED)
                totalAmount.assert().isEqualTo(totalAmount)
            }
        }
    }
})
