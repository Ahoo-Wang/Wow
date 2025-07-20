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

package me.ahoo.wow.example.domain.order

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.ChangeAddress
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.api.order.PayOrder
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.OrderFixture.SHIPPING_ADDRESS
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

class OrchestrationTest {

    @Suppress("LongMethod")
    @Test
    fun main() {
        val tenantId = generateGlobalId()

        val orderItem = CreateOrder.Item(
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }.map { it.quantity }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }
        aggregateVerifier<Order, OrderState>(tenantId = tenantId)
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false))
            .expectEventType(OrderCreated::class)
            .expectStateAggregate {
                it.aggregateId.tenantId.assert().isEqualTo(tenantId)
            }
            .expectState {
                it.id.assert().isNotNull()
                it.address.assert().isEqualTo(SHIPPING_ADDRESS)
                it.items.assert().hasSize(1)
                val item = it.items.first()
                item.productId.assert().isEqualTo(orderItem.productId)
                item.price.assert().isEqualTo(orderItem.price)
                item.quantity.assert().isEqualTo(orderItem.quantity)
                it.status.assert().isEqualTo(OrderStatus.CREATED)
            }.expect {
                it.exchange.getCommandResult().assert().hasSize(1)
                val result = it.exchange.getCommandResult<BigDecimal>(OrderState::totalAmount.name)
                result.assert().isEqualTo(orderItem.price.multiply(BigDecimal.valueOf(orderItem.quantity.toLong())))
            }
            .verify()
            .fork {
                changeAddress()
            }.fork {
                payOrder()
            }
    }

    private fun VerifiedStage<OrderState>.payOrder() {
        val payOrder = PayOrder(
            stateAggregate.aggregateId.id,
            stateAggregate.state.payable
        )
        whenCommand(payOrder)
            .expectEventType(OrderPaid::class)
            .expectState {
                it.paidAmount.assert().isEqualTo(it.totalAmount)
                it.status.assert().isEqualTo(OrderStatus.PAID)
            }
            .verify()
    }

    private fun VerifiedStage<OrderState>.changeAddress() {
        val changeAddress = ChangeAddress(
            ShippingAddress(
                country = "China",
                province = "ShangHai",
                city = "ShangHai",
                district = "HuangPu",
                detail = "002"
            )
        )
        whenCommand(changeAddress)
            .expectNoError()
            .expectEventType(AddressChanged::class)
            .expectState {
                it.address.assert().isEqualTo(changeAddress.shippingAddress)
            }
            .verify()
    }
}
