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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
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
 * A buyer's basket; its id is the buyer's user id, so each buyer has one. The first item creates it, and only its
 * buyer may: a caller with an owner other than the basket's id is rejected ([Rejections.NOT_OWNER]). After
 * that the kernel keeps the basket to the owner that created it.
 *
 * [CheckoutBasket] empties the basket into an order: [BasketCheckoutSaga] submits it to the ordering context. The
 * order's id is the basket's id and the number of the checkout (`alice-1`, `alice-2`, ...), so deciding again gives
 * the same event, and the caller is answered it as the result `orderId`.
 */
@AggregateRoot
@Description("A buyer's basket before checkout; its id is the buyer's user id")
class Basket(private val state: BasketState) {
    @OnCommand(produces = [BasketItemAdded::class])
    fun onCommand(command: Command<AddBasketItem>): Decision {
        val owner = command.scope.ownerId
        if (owner != null && owner != command.ref.id) {
            return Decision.reject(Rejections.NOT_OWNER, "The basket ${command.ref.id} is not $owner's.")
        }
        val adding = command.body
        if (adding.quantity <= 0 || adding.unitPrice.signum() < 0) {
            return Decision.reject(Rejections.INVALID_ITEM, "$adding is not a valid item.")
        }
        return Decision.events(BasketItemAdded(adding.productId, adding.unitPrice, adding.quantity))
    }

    /** Changing a quantity to the one the basket holds changes nothing. */
    @OnCommand(produces = [BasketItemQuantityChanged::class])
    fun onCommand(command: ChangeBasketItemQuantity): Decision {
        val item = state.item(command.productId) ?: return Decision.reject(
            Rejections.ITEM_NOT_FOUND,
            "The basket holds no ${command.productId}.",
        )
        return when {
            command.quantity <= 0 -> Decision.reject(
                Rejections.INVALID_ITEM,
                "${command.quantity} is not a positive quantity.",
            )

            command.quantity == item.quantity -> Decision.NoOp
            else -> Decision.events(BasketItemQuantityChanged(command.productId, command.quantity))
        }
    }

    /** Removing a product the basket does not hold changes nothing. */
    @OnCommand(produces = [BasketItemRemoved::class])
    fun onCommand(command: RemoveBasketItem): Decision =
        if (state.item(command.productId) == null) {
            Decision.NoOp
        } else {
            Decision.events(BasketItemRemoved(command.productId))
        }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand(produces = [BasketCleared::class])
    fun onCommand(command: ClearBasket): Decision =
        if (state.items.isEmpty()) Decision.NoOp else Decision.events(BasketCleared)

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    @OnCommand(produces = [BasketCheckedOut::class])
    fun onCommand(command: CheckoutBasket, scope: DecideScope): Decision {
        if (state.items.isEmpty()) {
            return Decision.reject(Rejections.EMPTY, "The basket is empty.")
        }
        val orderId = "${scope.command.ref.id}-${state.checkouts + 1}"
        scope.putResult(ORDER_ID_RESULT, orderId)
        return Decision.events(BasketCheckedOut(orderId, state.items))
    }

    companion object {
        /** The result [CheckoutBasket] answers the caller with: the id of the order it submits. */
        const val ORDER_ID_RESULT = "orderId"
    }

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.basket.<code>` (decision D259), as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "A basket's quantity is positive, and its price not negative.")
        const val INVALID_ITEM = "invalid-item"

        @ErrorCode(description = "The basket does not hold the product.", status = 404)
        const val ITEM_NOT_FOUND = "item-not-found"

        @ErrorCode(description = "An empty basket cannot be checked out.")
        const val EMPTY = "empty"

        @ErrorCode(description = "A basket belongs to the buyer whose id it has.", status = 403)
        const val NOT_OWNER = "not-owner"
    }
}
