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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The test DSL (roadmap 2.6) on the sample cart. */
class CartSpec {
    @Test
    fun `adding an item to a created cart`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"))
        whenCommand(AddItem("apple", 2)) {
            expectEvents(ItemAdded("apple", 2))
            expectState { items.assert().containsEntry("apple", 2) }
        }
        whenCommand(AddItem("apple", 1)) {
            expectEventTypes(ItemAdded::class.java)
            expectState { items.assert().containsEntry("apple", 3) }
        }
    }

    @Test
    fun `a full cart refuses a new product`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"), ItemAdded("a", 1), ItemAdded("b", 1))
        whenCommand(AddItem("c", 1)) { expectRejected("cart.full") }
    }

    @Test
    fun `clearing an empty cart changes nothing`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"))
        whenCommand(ClearCart("tidy")) { expectUnchanged() }
    }

    @Test
    fun `the state can be checked after a command that changed nothing`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"))
        whenCommand(ClearCart("tidy")) {
            expectUnchanged()
            expectState {
                owner.assert().isEqualTo("ahoo")
                items.assert().isEmpty()
            }
        }
    }

    @Test
    fun `the cart keeps the scope it was created in`() = aggregateSpec(cartModel) {
        whenCommand(CreateCart("ahoo"), scope = Scope("ahoo", "home")) { expectScope(Scope("ahoo", "home")) }
        whenCommand(AddItem("apple", 1), scope = Scope("ahoo", null)) { expectScope(Scope("ahoo", "home")) }
    }

    @Test
    fun `the cart refuses a command from another owner`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"), scope = Scope("ahoo", "home"))
        whenCommand(
            AddItem("apple", 1),
            scope = Scope("bob", "home")
        ) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
    }

    @Test
    fun `given events keep the aggregate's scope unless they set one`() = aggregateSpec(cartModel) {
        given(CartCreated("ahoo"), scope = Scope("ahoo", null))
        given(ItemAdded("a", 1))
        whenCommand(AddItem("b", 1)) { expectScope(Scope("ahoo", null)) }
    }

    @Test
    fun `a wrong expectation fails the specification`() {
        assertThrownBy<AssertionError> {
            aggregateSpec(cartModel) {
                given(CartCreated("ahoo"))
                whenCommand(AddItem("apple", 1)) { expectEvents(ItemAdded("pear", 1)) }
            }
        }
    }
}
