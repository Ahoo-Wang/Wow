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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision

data class CreateCart(val owner: String)
data class AddItem(val productId: String, val quantity: Int)
data class ClearCart(val reason: String)
data class CheckStock(val productId: String)

data class CartCreated(val owner: String)
data class ItemAdded(val productId: String, val quantity: Int)

class CartState(val id: String) {
    var owner: String? = null
    val items = mutableMapOf<String, Int>()
}

const val MAX_ITEMS = 2

/** A cart with mutable state, the default style (D7). */
val cartModel = aggregate<CartState>("example", "cart") {
    initial { CartState(it.id) }
    decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
    decide<AddItem> { state, command ->
        if (command.productId !in state.items && state.items.size >= MAX_ITEMS) {
            Decision.reject("cart.full", "A cart holds at most $MAX_ITEMS products.")
        } else {
            Decision.events(ItemAdded(command.productId, command.quantity))
        }
    }
    decide<ClearCart> { state, _ ->
        if (state.items.isEmpty()) {
            Decision.NoOp
        } else {
            Decision.reject(
                "cart.not-empty",
                "x"
            )
        }
    }
    decideSuspending<CheckStock> { _, command -> Decision.events(ItemAdded(command.productId, 0)) }
    evolve<CartCreated> { owner = it.owner }
    evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
}
