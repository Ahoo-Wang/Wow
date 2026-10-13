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

import java.math.BigDecimal

/** A line of an order: [quantity] units of [productId] at [unitPrice] each. */
data class OrderItem(
    val productId: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
)

/**
 * The life of an order, after the classic eShop's: submitted, awaiting the stock validation, its stock confirmed (it
 * then awaits its payment), paid and shipped; or cancelled before it is paid.
 */
enum class OrderStatus {
    SUBMITTED,
    AWAITING_VALIDATION,
    STOCK_CONFIRMED,
    PAID,
    SHIPPED,
    CANCELLED,
}
