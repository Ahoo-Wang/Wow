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

import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.example.eshop.basket.api.basket.AddBasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemAdded
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemQuantityChanged
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemRemoved
import me.ahoo.wow.example.eshop.basket.api.basket.ChangeBasketItemQuantity
import me.ahoo.wow.example.eshop.basket.api.basket.CheckoutBasket
import me.ahoo.wow.example.eshop.basket.api.basket.ClearBasket
import me.ahoo.wow.example.eshop.basket.api.basket.RemoveBasketItem

/**
 * A buyer's basket, one per buyer: its id is the buyer's user id ([AggregateIdSource.OWNER]). The buyer addresses it
 * by no id, as "mine", and the first item creates it; Wow forbids a buyer another buyer's basket, and only a caller
 * with no owner, such as a saga or a service, names the basket by its id.
 *
 * [CheckoutBasket] empties the basket into an order: [BasketCheckoutSaga] submits it to the ordering context, which
 * numbers it (`SO-261031-42`).
 */
@AggregateRoot(idSource = AggregateIdSource.OWNER)
@Description("A buyer's basket before checkout; its id is the buyer's user id")
class Basket(private val state: BasketState) {
    // region add-item
    fun onCommand(command: AddBasketItem): BasketItemAdded =
        BasketItemAdded(command.productId, command.unitPrice, command.quantity)
    // endregion

    /** Changing a quantity to the one the basket holds changes nothing. */
    fun onCommand(command: ChangeBasketItemQuantity): BasketItemQuantityChanged? {
        val item = state.item(command.productId)
            ?: reject(Rejections.ITEM_NOT_FOUND, "The basket holds no ${command.productId}.")
        return if (command.quantity == item.quantity) {
            null
        } else {
            BasketItemQuantityChanged(command.productId, command.quantity)
        }
    }

    /** Removing a product the basket does not hold changes nothing. */
    fun onCommand(command: RemoveBasketItem): BasketItemRemoved? =
        if (state.item(command.productId) == null) null else BasketItemRemoved(command.productId)

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: ClearBasket): BasketCleared? = if (state.items.isEmpty()) null else BasketCleared

    // region checkout
    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: CheckoutBasket): BasketCheckedOut {
        if (state.items.isEmpty()) {
            reject(Rejections.EMPTY, "The basket is empty.")
        }
        return BasketCheckedOut(state.items)
    }
    // endregion

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.basket.<code>`, as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "The basket does not hold the product.", status = 404)
        const val ITEM_NOT_FOUND = "item-not-found"

        @ErrorCode(description = "An empty basket cannot be checked out.")
        const val EMPTY = "empty"
    }
}
