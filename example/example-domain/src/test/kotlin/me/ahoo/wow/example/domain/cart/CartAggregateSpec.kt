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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.RemoveCartItem
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.command.IllegalAccessDeletedAggregateException
import me.ahoo.wow.test.AggregateSpec

class CartAggregateSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            val ownerId = generateGlobalId()
            val addCartItem = AddCartItem(
                productId = "productId",
                quantity = 1,
            )
            givenOwnerId(ownerId)
            whenCommand(addCartItem) {
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState {
                    items.assert().hasSize(1)
                }
                expectStateAggregate {
                    ownerId.assert().isEqualTo(ownerId)
                }
                fork {
                    val removeCartItem = RemoveCartItem(
                        productIds = setOf(addCartItem.productId),
                    )
                    whenCommand(removeCartItem) {
                        expectEventType(CartItemRemoved::class)
                    }
                }
                fork {
                    whenCommand(DefaultDeleteAggregate) {
                        expectEventType(DefaultAggregateDeleted::class)
                        expectStateAggregate {
                            deleted.assert().isTrue()
                        }

                        fork {
                            whenCommand(DefaultDeleteAggregate) {
                                expectErrorType(IllegalAccessDeletedAggregateException::class)
                            }
                        }
                        fork {
                            whenCommand(DefaultRecoverAggregate) {
                                expectNoError()
                                expectStateAggregate {
                                    deleted.assert().isFalse()
                                }
                                fork {
                                    whenCommand(DefaultRecoverAggregate) {
                                        expectErrorType(IllegalStateException::class)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
)
