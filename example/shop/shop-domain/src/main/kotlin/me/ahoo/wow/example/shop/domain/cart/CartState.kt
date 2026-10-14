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

package me.ahoo.wow.example.shop.domain.cart

import me.ahoo.wow.example.shop.api.cart.CartItem
import me.ahoo.wow.example.shop.api.cart.CartItemAdded
import me.ahoo.wow.example.shop.api.cart.CartItemRemoved
import me.ahoo.wow.example.shop.api.cart.CartQuantityChanged

/** The cart's state, changed in place by its events (the default, mutable style). */
class CartState {
    var items: List<CartItem> = emptyList()
        private set

    fun onSourcing(event: CartItemAdded) {
        items = items + event.added
    }

    fun onSourcing(event: CartItemRemoved) {
        items = items.filterNot { it.productId in event.productIds }
    }

    fun onSourcing(event: CartQuantityChanged) {
        items = items.map { if (it.productId == event.changed.productId) event.changed else it }
    }
}
