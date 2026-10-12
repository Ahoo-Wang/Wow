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

package me.ahoo.wow.example.shop.api.cart

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary

/*
 * A cart's id is its customer's user id, taken from the route (`/example/cart/{id}/...`): no command carries an
 * identity field (decision D68).
 */

/** Adds [quantity] of [productId] to the cart, creating the cart on its first item. */
@AllowCreate
@Summary("Add an item to the cart")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1,
)

/** Removes [productIds] from the cart. */
@Summary("Remove items from the cart")
data class RemoveCartItem(
    val productIds: Set<String>,
)

/** Sets the quantity of [productId], already in the cart, to [quantity]. */
@Summary("Change an item's quantity")
data class ChangeQuantity(
    val productId: String,
    val quantity: Int,
)
