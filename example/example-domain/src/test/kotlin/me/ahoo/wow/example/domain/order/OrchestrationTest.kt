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
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.aggregate.ExpectedResult
import me.ahoo.wow.test.aggregate.GivenStage
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

class OrchestrationTest {

    @Suppress("LongMethod")
    @Test
    fun main() {
        val tenantId = GlobalIdGenerator.generateAsString()

        val orderItem = CreateOrder.Item(
            productId = GlobalIdGenerator.generateAsString(),
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
            .expectEventType(OrderCreated::class.java)
            .expectStateAggregate {
                assertThat(it.aggregateId.tenantId, equalTo(tenantId))
            }
            .expectState {
                assertThat(it.id, notNullValue())
                assertThat(it.address, equalTo(SHIPPING_ADDRESS))
                assertThat(it.items, hasSize(1))
                val item = it.items.first()
                assertThat(item.productId, equalTo(orderItem.productId))
                assertThat(item.price, equalTo(orderItem.price))
                assertThat(item.quantity, equalTo(orderItem.quantity))
                assertThat(it.status, equalTo(OrderStatus.CREATED))
            }.expect {
                assertThat(it.exchange.getCommandResult().size, equalTo(1))
                val result = it.exchange.getCommandResult<BigDecimal>(OrderState::totalAmount.name)
                assertThat(result, equalTo(orderItem.price.multiply(BigDecimal.valueOf(orderItem.quantity.toLong()))))
            }
            .verify()
            .fork {
                changeAddress(it)
            }.fork {
                payOrder(it)
            }
    }

    private fun GivenStage<OrderState>.payOrder(it: ExpectedResult<OrderState>) {
        val payOrder = PayOrder(
            it.stateAggregate.aggregateId.id,
            it.stateAggregate.state.payable
        )
        whenCommand(payOrder)
            .expectEventType(OrderPaid::class.java)
            .expectState {
                assertThat(it.paidAmount, equalTo(it.totalAmount))
                assertThat(it.status, equalTo(OrderStatus.PAID))
            }
            .verify()
    }

    private fun GivenStage<OrderState>.changeAddress(it: ExpectedResult<OrderState>) {
        val changeAddress = ChangeAddress(
            ShippingAddress(
                country = "China",
                province = "ShangHai",
                city = "ShangHai",
                district = "HuangPu",
                detail = "002"
            )
        )
        whenCommand(changeAddress).expectNoError()
            .expectEventCount(1)
            .expectEventType(AddressChanged::class.java)
            .expectState {
                assertThat(it.address, equalTo(changeAddress.shippingAddress))
            }
            .verify()
    }
}
