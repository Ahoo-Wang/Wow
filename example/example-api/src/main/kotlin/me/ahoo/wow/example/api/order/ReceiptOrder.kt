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

import jakarta.validation.constraints.NotBlank
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.example.api.ExampleService

/**
 * ReceiptOrder .
 *
 * @author ahoo wang
 */
@Summary("收货")
@CommandRoute(
    prefix = ExampleService.CUSTOMER_ORDER_PREFIX,
    path = "package",
    appendIdPath = CommandRoute.AppendPath.ALWAYS,
    method = CommandRoute.Method.PATCH
)
data class ReceiptOrder(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
    @field:NotBlank
    @CommandRoute.PathVariable
    val customerId: String
)

object OrderReceived
