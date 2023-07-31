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

import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.example.api.cart.ChangeQuantity
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CartTest {
    companion object {
        const val MOCK_CUSTOMER_ID = "customerId"
    }

    @Test
    fun addCartItem() {
        val addCartItem = AddCartItem(
            id = GlobalIdGenerator.generateAsString(),
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given()
            .`when`(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                assertThat(it.items, hasSize(1))
            }
            .verify()
    }

    @Test
    fun addCartItemIfSameProduct() {
        val addCartItem = AddCartItem(
            id = GlobalIdGenerator.generateAsString(),
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = CartItem(
                        productId = addCartItem.productId,
                        quantity = 1,
                    ),
                ),
            )
            .`when`(addCartItem)
            .expectNoError()
            .expectEventType(CartQuantityChanged::class.java)
            .expectState {
                assertThat(it.items.first().quantity, equalTo(2))
            }
            .verify()
    }

    @Test
    fun addCartItemIfUnCreated() {
        val addCartItem = AddCartItem(
            id = MOCK_CUSTOMER_ID,
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>()
            .given()
            .`when`(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                assertThat(it.items, hasSize(1))
            }
            .expectStateAggregate {
                assertThat(it.version, equalTo(1))
            }
            .verify()
    }

    @Test
    fun addCartItemGivenMax() {
        val events = buildList {
            for (i in 0..99) {
                add(
                    CartItemAdded(
                        added = CartItem(
                            productId = "productId$i",
                            quantity = 1,
                        ),
                    ),
                )
            }
        }.toTypedArray()
        val addCartItem = AddCartItem(
            id = GlobalIdGenerator.generateAsString(),
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(*events)
            .`when`(addCartItem)
            .expectErrorType(IllegalArgumentException::class.java)
            .expectState {
                assertThat(it.items, hasSize(MAX_CART_ITEM_SIZE))
            }
            .verify()
    }

    @Test
    fun removeCartItem() {
        val removeCartItem = RemoveCartItem(
            id = GlobalIdGenerator.generateAsString(),
            productIds = setOf("productId"),
        )
        val added = CartItem(
            productId = "productId",
            quantity = 1,
        )

        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = added,
                ),
            )
            .`when`(removeCartItem)
            .expectEventType(CartItemRemoved::class.java)
            .expectState {
                assertThat(it.items, hasSize(0))
            }
            .verify()
    }

    @Test
    fun changeQuantity() {
        val changeQuantity = ChangeQuantity(
            id = GlobalIdGenerator.generateAsString(),
            productId = "productId",
            quantity = 2,
        )
        val added = CartItem(
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>()
            .given(
                CartItemAdded(
                    added = added,
                ),
            )
            .`when`(changeQuantity)
            .expectEventType(CartQuantityChanged::class.java)
            .expectState {
                assertThat(it.items, hasSize(1))
                assertThat(it.items.first().quantity, equalTo(changeQuantity.quantity))
            }
            .verify()
    }
}
