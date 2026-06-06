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

package me.ahoo.wow.example.domain.cart

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.example.api.cart.ICartInfo

class CartState(val id: String) : ICartInfo {
    override var items: List<CartItem> = ArrayList()
        private set

    @Suppress("UNCHECKED_CAST")
    private fun mutableItems(): MutableList<CartItem> {
        if (items is MutableList<*>) {
            return items as MutableList<CartItem>
        }
        val mutableItems = ArrayList(items)
        items = mutableItems
        return mutableItems
    }

    @OnSourcing
    fun onCartItemAdded(cartItemAdded: CartItemAdded) {
        mutableItems().add(cartItemAdded.added)
    }

    @OnSourcing
    fun onCartItemRemoved(cartItemRemoved: CartItemRemoved) {
        mutableItems().removeAll { cartItemRemoved.productIds.contains(it.productId) }
    }

    @OnSourcing
    fun onCartQuantityChanged(cartQuantityChanged: CartQuantityChanged) {
        val mutableItems = mutableItems()
        for (index in mutableItems.indices) {
            if (mutableItems[index].productId == cartQuantityChanged.changed.productId) {
                mutableItems[index] = cartQuantityChanged.changed
            }
        }
    }
}
