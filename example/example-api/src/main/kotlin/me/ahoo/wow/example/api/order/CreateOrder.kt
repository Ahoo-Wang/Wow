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
package me.ahoo.wow.example.api.order

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.example.api.ExampleService
import java.math.BigDecimal

/**
 * CreateOrder .
 *
 * @author ahoo wang
 */
@Summary("下单")
@CommandRoute(
    prefix = ExampleService.CUSTOMER_ORDER_PREFIX,
    path = ""
)
@CreateAggregate
data class CreateOrder(
    @field:NotBlank
    @field:CommandRoute.PathVariable
    val customerId: String,
    @field:Size(min = 1)
    val items: List<Item>,
    @field:NotNull
    @field:Valid
    val address: ShippingAddress,
    val fromCart: Boolean
) {
    data class Item(
        override val productId: String,
        override val price: BigDecimal,
        override val quantity: Int
    ) : CreateOrderItem
}

data class OrderCreated(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)
