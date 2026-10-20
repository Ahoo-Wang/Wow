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

package me.ahoo.wow.rehearsal.example

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision

data class CartItem(val productId: String, val quantity: Int)

data class AddCartItem(val productId: String, val quantity: Int = 1)

data class ChangeQuantity(val productId: String, val quantity: Int)

data class RemoveCartItem(val productIds: Set<String>)

data class CartItemAdded(val added: CartItem)

data class CartQuantityChanged(val changed: CartItem)

data class CartItemRemoved(val productIds: Set<String>)

/** The 9.x cart's state; its id is the ref's, the customer's id, in V10. */
class CartState {
    var items: List<CartItem> = emptyList()
        private set

    val size: Int
        get() = items.size

    fun onSourcing(event: CartItemAdded) {
        items = items + event.added
    }

    fun onSourcing(event: CartQuantityChanged) {
        items = items.map { if (it.productId == event.changed.productId) event.changed else it }
    }

    fun onSourcing(event: CartItemRemoved) {
        items = items.filterNot { it.productId in event.productIds }
    }
}

/** The 9.x cart's decisions: a customer's cart, at most [MAX_ITEMS] products. */
@AggregateRoot
class Cart(private val state: CartState) {
    @OnCommand(produces = [CartItemAdded::class, CartQuantityChanged::class])
    fun onCommand(command: AddCartItem): Decision {
        state.items.firstOrNull { it.productId == command.productId }?.let {
            return Decision.events(CartQuantityChanged(it.copy(quantity = it.quantity + command.quantity)))
        }
        if (state.items.size >= MAX_ITEMS) {
            return Decision.reject(Rejections.FULL, "A cart holds at most $MAX_ITEMS products.")
        }
        return Decision.events(CartItemAdded(CartItem(command.productId, command.quantity)))
    }

    @OnCommand(produces = [CartItemRemoved::class])
    fun onCommand(command: RemoveCartItem): Decision = Decision.events(CartItemRemoved(command.productIds))

    @OnCommand(produces = [CartQuantityChanged::class])
    fun onCommand(command: ChangeQuantity): Decision {
        val item = state.items.firstOrNull { it.productId == command.productId }
            ?: return Decision.reject(Rejections.NO_SUCH_ITEM, "The cart has no product ${command.productId}.")
        return Decision.events(CartQuantityChanged(item.copy(quantity = command.quantity)))
    }

    object Rejections {
        @ErrorCode(description = "The cart is full.", status = 409)
        const val FULL = "full"

        @ErrorCode(description = "The cart has no such product.", status = 404)
        const val NO_SUCH_ITEM = "no-such-item"
    }

    companion object {
        const val MAX_ITEMS = 100
    }
}
