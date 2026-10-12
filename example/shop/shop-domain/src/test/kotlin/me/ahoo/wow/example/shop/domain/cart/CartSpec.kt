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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.api.cart.AddCartItem
import me.ahoo.wow.example.shop.api.cart.CartItem
import me.ahoo.wow.example.shop.api.cart.CartItemAdded
import me.ahoo.wow.example.shop.api.cart.CartItemRemoved
import me.ahoo.wow.example.shop.api.cart.CartQuantityChanged
import me.ahoo.wow.example.shop.api.cart.CartRejections
import me.ahoo.wow.example.shop.api.cart.ChangeQuantity
import me.ahoo.wow.example.shop.api.cart.RemoveCartItem
import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.test.AggregateSpec
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The cart on the kernel, through the generated model. A cart's id is its customer's user id. */
class CartSpec {
    private val model = cartModel(ExampleContext.NAME)

    private fun cartSpec(block: AggregateSpec<CartState>.() -> Unit) = aggregateSpec(
        model,
        id = CUSTOMER,
        block = block
    )

    @Test
    fun `adding the first item should create the cart`() = cartSpec {
        whenCommand(AddCartItem("apple", 2), scope = Scope(CUSTOMER)) {
            expectEvents(CartItemAdded(CartItem("apple", 2)))
            expectScope(Scope(CUSTOMER))
            expectState { items.assert().containsExactly(CartItem("apple", 2)) }
        }
    }

    @Test
    fun `adding a product the cart holds should raise its quantity`() = cartSpec {
        given(CartItemAdded(CartItem("apple", 2)))
        whenCommand(AddCartItem("apple", 1)) {
            expectEvents(CartQuantityChanged(CartItem("apple", 3)))
            expectState { items.assert().containsExactly(CartItem("apple", 3)) }
        }
    }

    @Test
    fun `a customer should not add to another customer's cart`() = cartSpec {
        whenCommand(AddCartItem("apple"), scope = Scope("mallory")) { expectRejected(CartRejections.NOT_OWNER) }
    }

    @Test
    fun `the kernel should refuse another owner once the cart exists`() = cartSpec {
        given(CartItemAdded(CartItem("apple")), scope = Scope(CUSTOMER))
        whenCommand(RemoveCartItem(setOf("apple")), scope = Scope("mallory")) {
            expectRejected(KernelRejections.FORBIDDEN)
        }
    }

    @Test
    fun `a full cart should refuse a new product but not more of one it holds`() = cartSpec {
        given(*Array(MAX_CART_ITEM_SIZE) { CartItemAdded(CartItem("product-$it")) })
        whenCommand(AddCartItem("apple")) { expectRejected(CartRejections.FULL) }
        whenCommand(AddCartItem("product-0")) { expectEvents(CartQuantityChanged(CartItem("product-0", 2))) }
    }

    @Test
    fun `removing items should remove those the cart holds`() = cartSpec {
        given(CartItemAdded(CartItem("apple")), CartItemAdded(CartItem("pear")))
        whenCommand(RemoveCartItem(setOf("apple", "kiwi"))) {
            expectEvents(CartItemRemoved(setOf("apple")))
            expectState { items.assert().containsExactly(CartItem("pear")) }
        }
    }

    @Test
    fun `removing items the cart does not hold should change nothing`() = cartSpec {
        given(CartItemAdded(CartItem("apple")))
        whenCommand(RemoveCartItem(setOf("kiwi"))) { expectUnchanged() }
    }

    @Test
    fun `removing from a cart that does not exist should be refused`() = cartSpec {
        whenCommand(RemoveCartItem(setOf("apple"))) { expectRejected(KernelRejections.NOT_FOUND) }
    }

    @Test
    fun `changing the quantity should set it`() = cartSpec {
        given(CartItemAdded(CartItem("apple")), CartItemAdded(CartItem("pear")))
        whenCommand(ChangeQuantity("apple", 5)) {
            expectEvents(CartQuantityChanged(CartItem("apple", 5)))
            expectState { items.assert().containsExactly(CartItem("apple", 5), CartItem("pear")) }
        }
    }

    @Test
    fun `changing the quantity of a product the cart does not hold should be rejected`() = cartSpec {
        given(CartItemAdded(CartItem("apple")))
        whenCommand(ChangeQuantity("kiwi", 5)) { expectRejected(CartRejections.ITEM_NOT_FOUND) }
    }

    private companion object {
        const val CUSTOMER = "ahoo"
    }
}
