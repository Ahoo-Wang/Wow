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

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
import javax.validation.constraints.Size

/**
 * CreateOrder .
 *
 * @author ahoo wang
 */
@Summary("创建订单")
@CommandRoute(
    "customer/{customerId}/order",
    ignoreAggregateNamePrefix = true,
)
@CreateAggregate
data class CreateOrder(
    @field:NotBlank
    @CommandRoute.PathVariable
    val customerId: String,
    @field:Size(min = 1)
    val items: List<OrderItem>,
    @field:NotNull
    @Valid
    val address: ShippingAddress,
    val fromCart: Boolean
)

data class OrderCreated(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)
