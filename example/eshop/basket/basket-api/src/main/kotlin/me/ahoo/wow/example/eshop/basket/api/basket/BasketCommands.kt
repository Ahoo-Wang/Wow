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

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/*
 * A basket's id is its buyer's user id, taken from the route (`/eshop-basket/basket/{buyer}/...`): no command carries
 * an identity field.
 */

/**
 * Adds [quantity] of [productId] at [unitPrice] to the basket, creating the basket on its first item. Adding a product
 * already in the basket adds to its quantity, at the new price.
 */
@AllowCreate
@Summary("Add an item to the basket")
data class AddBasketItem(
    val productId: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
)

/** Changes the quantity of [productId] in the basket to [quantity]. */
@Summary("Change an item's quantity")
data class ChangeBasketItemQuantity(
    val productId: String,
    val quantity: Int,
)

/** Removes [productId] from the basket. */
@Summary("Remove an item from the basket")
data class RemoveBasketItem(
    val productId: String,
)

/** Empties the basket. */
@Summary("Clear the basket")
data object ClearBasket

/**
 * Checks the basket out: its items become an order in the ordering context, and the basket is emptied. The order's id
 * is answered as the result `orderId`.
 */
@Summary("Check the basket out")
data object CheckoutBasket
