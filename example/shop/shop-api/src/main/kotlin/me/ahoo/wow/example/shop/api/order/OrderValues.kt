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

package me.ahoo.wow.example.shop.api.order

import java.math.BigDecimal

/** An item of an order, [id] within it: [quantity] of [productId] at the unit [price]. */
data class OrderItem(
    val id: String,
    val productId: String,
    val price: BigDecimal,
    val quantity: Int,
) {
    /** The item's total: its unit price times its quantity. A function, so it is not part of the event's JSON. */
    fun totalPrice(): BigDecimal = price.multiply(quantity.toBigDecimal())
}

data class ShippingAddress(
    val country: String,
    val province: String,
    val city: String,
    val district: String,
    val detail: String,
)

enum class OrderStatus {
    CREATED,
    PAID,
    SHIPPED,
    RECEIVED,
    CANCELLED,
}
