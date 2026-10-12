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

/** A product the cart holds, [quantity] times. */
data class CartItem(
    val productId: String,
    val quantity: Int = 1,
)

/** The codes a cart rejects a command with, besides the framework's. */
object CartRejections {
    /** The cart already holds its maximum number of products. */
    const val FULL = "example.cart.full"

    /** The product is not in the cart. */
    const val ITEM_NOT_FOUND = "example.cart.item-not-found"

    /** The caller is not the customer whose cart this is: a cart's id is its customer's user id. */
    const val NOT_OWNER = "example.cart.not-owner"
}
