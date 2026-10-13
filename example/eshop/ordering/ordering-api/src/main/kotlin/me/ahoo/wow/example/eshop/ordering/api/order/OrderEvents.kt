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

package me.ahoo.wow.example.eshop.ordering.api.order

import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

@Summary("An order was submitted")
data class OrderSubmitted(
    val buyerId: String,
    val items: List<OrderItem>,
)

@Summary("The order awaits its stock validation")
data object OrderAwaitingValidation

@Summary("The inventory reserved one of the order's products")
data class OrderStockItemConfirmed(
    val productId: String,
)

@Summary("The inventory reserved every product of the order; it awaits its payment")
data object OrderStockConfirmed

@Summary("The order was paid")
data class OrderPaid(
    val paymentId: String,
    val amount: BigDecimal,
)

@Summary("The order was shipped")
data object OrderShipped

@Summary("The order was cancelled")
data class OrderCancelled(
    val reason: String,
)
