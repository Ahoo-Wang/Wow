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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.example.eshop.basket.api.basket.AddBasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCheckedOut
import me.ahoo.wow.example.eshop.basket.api.basket.BasketCleared
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemAdded
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemQuantityChanged
import me.ahoo.wow.example.eshop.basket.api.basket.BasketItemRemoved
import me.ahoo.wow.example.eshop.basket.api.basket.ChangeBasketItemQuantity
import me.ahoo.wow.example.eshop.basket.api.basket.CheckoutBasket
import me.ahoo.wow.example.eshop.basket.api.basket.ClearBasket
import me.ahoo.wow.example.eshop.basket.api.basket.EshopBasketContract
import me.ahoo.wow.example.eshop.basket.api.basket.RemoveBasketItem
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The basket on the kernel, through the model the compiler generated. */
class BasketSpec {
    private val model = basketModel(EshopBasketContract.NAME)

    private fun basketSpec(block: AggregateSpec<BasketState>.() -> Unit) = aggregateSpec(
        model,
        id = BUYER,
        block = block
    )

    @Test
    fun `adding the first item should create the basket`() = basketSpec {
        whenCommand(AddBasketItem(MUG, MUG_PRICE, 2), scope = Scope(ownerId = BUYER)) {
            expectEvents(BasketItemAdded(MUG, MUG_PRICE, 2))
            expectScope(Scope(ownerId = BUYER))
            expectState { items.assert().containsExactly(BasketItem(MUG, MUG_PRICE, 2)) }
        }
    }

    @Test
    fun `another buyer should not create the basket`() = basketSpec {
        whenCommand(AddBasketItem(MUG, MUG_PRICE, 2), scope = Scope(ownerId = "bob")) {
            expectRejected(Basket.Rejections.NOT_OWNER)
        }
    }

    @Test
    fun `adding a product again should add to its quantity at the new price`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2))
        whenCommand(AddBasketItem(MUG, BigDecimal("9.00"), 1)) {
            expectEvents(BasketItemAdded(MUG, BigDecimal("9.00"), 1))
            expectState { items.assert().containsExactly(BasketItem(MUG, BigDecimal("9.00"), 3)) }
        }
    }

    @Test
    fun `changing a quantity should replace it`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2), BasketItemAdded(CAP, CAP_PRICE, 1))
        whenCommand(ChangeBasketItemQuantity(MUG, 5)) {
            expectEvents(BasketItemQuantityChanged(MUG, 5))
            expectState {
                items.assert().containsExactly(BasketItem(MUG, MUG_PRICE, 5), BasketItem(CAP, CAP_PRICE, 1))
            }
        }
        whenCommand(ChangeBasketItemQuantity(MUG, 5)) { expectUnchanged() }
        whenCommand(ChangeBasketItemQuantity("hat", 1)) { expectRejected(Basket.Rejections.ITEM_NOT_FOUND) }
    }

    @Test
    fun `removing an item should take it out of the basket`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2), BasketItemAdded(CAP, CAP_PRICE, 1))
        whenCommand(RemoveBasketItem(MUG)) {
            expectEvents(BasketItemRemoved(MUG))
            expectState { items.assert().containsExactly(BasketItem(CAP, CAP_PRICE, 1)) }
        }
        whenCommand(RemoveBasketItem(MUG)) { expectUnchanged() }
    }

    @Test
    fun `clearing should empty the basket`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2))
        whenCommand(ClearBasket) {
            expectEvents(BasketCleared)
            expectState { items.assert().isEmpty() }
        }
        whenCommand(ClearBasket) { expectUnchanged() }
    }

    @Test
    fun `checking out should empty the basket into a numbered order`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2), BasketItemAdded(CAP, CAP_PRICE, 1))
        val items = listOf(BasketItem(MUG, MUG_PRICE, 2), BasketItem(CAP, CAP_PRICE, 1))
        whenCommand(CheckoutBasket) {
            expectEvents(BasketCheckedOut("$BUYER-1", items))
            expectResult(Basket.ORDER_ID_RESULT, "$BUYER-1")
            expectState {
                this.items.assert().isEmpty()
                checkouts.assert().isEqualTo(1)
            }
        }
        whenCommand(AddBasketItem(MUG, MUG_PRICE, 1)) { expectEventTypes(BasketItemAdded::class.java) }
        whenCommand(
            CheckoutBasket
        ) { expectEvents(BasketCheckedOut("$BUYER-2", listOf(BasketItem(MUG, MUG_PRICE, 1)))) }
    }

    @Test
    fun `an empty basket should not be checked out`() = basketSpec {
        given(BasketItemAdded(MUG, MUG_PRICE, 2), BasketCleared)
        whenCommand(CheckoutBasket) { expectRejected(Basket.Rejections.EMPTY) }
    }

    private companion object {
        const val BUYER = "alice"
        const val MUG = "mug"
        const val CAP = "cap"
        val MUG_PRICE = BigDecimal("8.50")
        val CAP_PRICE = BigDecimal("12.00")
    }
}
