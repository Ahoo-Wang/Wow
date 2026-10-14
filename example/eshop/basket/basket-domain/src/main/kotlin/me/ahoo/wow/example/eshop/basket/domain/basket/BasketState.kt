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

package me.ahoo.wow.example.eshop.basket.domain.basket

import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemAdded
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemQuantityChanged
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemRemoved

/**
 * The state of a [Basket], changed in place by its events: its [items], one line per product, and how many times it
 * was [checked out][checkouts], which numbers its orders. Every property has a value before the first event.
 */
class BasketState {
    var items: List<BasketItem> = emptyList()
        private set

    var checkouts: Int = 0
        private set

    /** The line of [productId], null when the basket holds none. */
    fun item(productId: String): BasketItem? = items.firstOrNull { it.productId == productId }

    fun onSourcing(event: BasketItemAdded) {
        val existing = item(event.productId)
        items = if (existing == null) {
            items + BasketItem(event.productId, event.unitPrice, event.quantity)
        } else {
            replace(existing.copy(unitPrice = event.unitPrice, quantity = existing.quantity + event.quantity))
        }
    }

    fun onSourcing(event: BasketItemQuantityChanged) {
        items = replace(checkNotNull(item(event.productId)).copy(quantity = event.quantity))
    }

    fun onSourcing(event: BasketItemRemoved) {
        items = items.filterNot { it.productId == event.productId }
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: BasketCleared) {
        items = emptyList()
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: BasketCheckedOut) {
        items = emptyList()
        checkouts += 1
    }

    private fun replace(line: BasketItem): List<BasketItem> =
        items.map { if (it.productId == line.productId) line else it }
}
