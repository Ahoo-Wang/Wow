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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.shop.api.cart.AddCartItem
import me.ahoo.wow.example.shop.api.cart.CartItem
import me.ahoo.wow.example.shop.api.cart.CartItemAdded
import me.ahoo.wow.example.shop.api.cart.CartItemRemoved
import me.ahoo.wow.example.shop.api.cart.CartQuantityChanged
import me.ahoo.wow.example.shop.api.cart.CartRejections
import me.ahoo.wow.example.shop.api.cart.ChangeQuantity
import me.ahoo.wow.example.shop.api.cart.RemoveCartItem

/** How many products a cart holds at most. */
const val MAX_CART_ITEM_SIZE = 100

/**
 * A customer's cart. Its id is the customer's user id: the route names it (`/example/cart/{id}/add-cart-item`), and
 * [AddCartItem], which creates the cart, checks that the caller is that customer. Once the cart exists, the kernel's
 * scope guard (decision D94) refuses a caller of another owner, so the other commands need no check of their own.
 * This replaces 9.x's `@AggregateOwner(OwnerPolicy.AGGREGATE_ID)`.
 */
@AggregateRoot
@Description("A customer's cart; its id is the customer's user id")
class Cart(private val state: CartState) {
    /**
     * Takes the admitted command, not only its body, to compare the caller's owner with the cart's id. A product
     * already in the cart gets its quantity raised instead of a second line.
     */
    fun onCommand(command: Command<AddCartItem>): Decision {
        val owner = command.scope.ownerId
        if (owner != null && owner != command.ref.id) {
            return Decision.reject(CartRejections.NOT_OWNER, "The cart ${command.ref.id} is not $owner's.")
        }
        val adding = command.body
        val existing = state.items.firstOrNull { it.productId == adding.productId }
        if (existing != null) {
            return Decision.events(CartQuantityChanged(existing.copy(quantity = existing.quantity + adding.quantity)))
        }
        if (state.items.size >= MAX_CART_ITEM_SIZE) {
            return Decision.reject(CartRejections.FULL, "A cart holds at most $MAX_CART_ITEM_SIZE products.")
        }
        return Decision.events(CartItemAdded(CartItem(adding.productId, adding.quantity)))
    }

    /** Removing products the cart does not hold changes nothing. */
    fun onCommand(command: RemoveCartItem): Decision {
        val removed = command.productIds.filterTo(linkedSetOf()) { productId ->
            state.items.any { it.productId == productId }
        }
        return if (removed.isEmpty()) Decision.NoOp else Decision.events(CartItemRemoved(removed))
    }

    fun onCommand(command: ChangeQuantity): Decision {
        val item = state.items.firstOrNull { it.productId == command.productId }
            ?: return Decision.reject(
                CartRejections.ITEM_NOT_FOUND,
                "The product ${command.productId} is not in the cart.",
            )
        return Decision.events(CartQuantityChanged(item.copy(quantity = command.quantity)))
    }
}
