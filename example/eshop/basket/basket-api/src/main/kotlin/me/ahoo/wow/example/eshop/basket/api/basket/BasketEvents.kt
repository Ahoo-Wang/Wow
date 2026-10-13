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

package me.ahoo.wow.example.eshop.basket.api.basket

import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/** [quantity] of [productId] was added to the basket, at [unitPrice]. */
@Summary("An item was added to the basket")
data class BasketItemAdded(
    val productId: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
)

@Summary("An item's quantity changed")
data class BasketItemQuantityChanged(
    val productId: String,
    val quantity: Int,
)

@Summary("An item was removed from the basket")
data class BasketItemRemoved(
    val productId: String,
)

@Summary("The basket was cleared")
data object BasketCleared

/** The basket was checked out as the order [orderId] of [items]; it is empty again. */
@Summary("The basket was checked out")
data class BasketCheckedOut(
    val orderId: String,
    val items: List<BasketItem>,
)
