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

import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.api.order.CreateOrder
import me.ahoo.wow.example.shop.api.order.OrderCreated
import me.ahoo.wow.example.shop.api.order.OrderItem
import me.ahoo.wow.example.shop.api.order.ShippingAddress
import java.math.BigDecimal

/** The price of each product it lists. */
class StubPricingService(private val prices: Map<String, BigDecimal>) : PricingService {
    override suspend fun getProductPrice(productId: String): BigDecimal =
        requireNotNull(prices[productId]) { "No price for $productId." }
}

object OrderFixtures {
    const val ORDER_ID = "order-1"
    val APPLE_PRICE: BigDecimal = BigDecimal("2.50")
    val PEAR_PRICE: BigDecimal = BigDecimal("4.00")
    val HOME = ShippingAddress("China", "Shanghai", "Shanghai", "Pudong", "1 Century Avenue")
    val OFFICE = ShippingAddress("China", "Beijing", "Beijing", "Haidian", "2 Zhongguancun Street")

    val pricing = StubPricingService(mapOf("apple" to APPLE_PRICE, "pear" to PEAR_PRICE))

    /** The order model with the stub service, as the host builds it. */
    val model = orderModel(ExampleContext.NAME, pricing)

    /** Two apples and a pear: 9.00 in all. */
    val createOrder = CreateOrder(
        items = listOf(CreateOrder.Item("apple", APPLE_PRICE, 2), CreateOrder.Item("pear", PEAR_PRICE, 1)),
        address = HOME,
        fromCart = true,
    )

    val orderCreated = OrderCreated(
        items = listOf(
            OrderItem("$ORDER_ID-1", "apple", APPLE_PRICE, 2),
            OrderItem("$ORDER_ID-2", "pear", PEAR_PRICE, 1),
        ),
        address = HOME,
        fromCart = true,
    )

    val total: BigDecimal = BigDecimal("9.00")
}
