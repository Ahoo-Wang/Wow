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
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.decide.Decision
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The DSL names types and aggregates as the compiler does from their annotations (decision D55, finding R5.A-5). */
class AnnotatedNamesTest {
    class Cart(val id: String)

    @Name("PutItem")
    data class AddItem(val sku: String)

    @Name("ItemPut")
    data class ItemAdded(val sku: String)

    data class ItemRemoved(val sku: String)

    @AggregateRoot
    class ShoppingCart(val id: String)

    @AggregateRoot
    @Name("basket")
    class NamedCart(val id: String)

    @Test
    fun `a type's @Name is its default name, as the compiler gives it`() {
        val model = aggregate<Cart>("shop", "cart") {
            initial { Cart(it.id) }
            decide<AddItem> { _, body -> Decision.events(ItemAdded(body.sku)) }
            mount<ItemRemoved>()
            evolve<ItemAdded> { }
        }
        model.commandTypes.first().name.assert().isEqualTo("shop.cart.PutItem")
        model.eventType(ItemAdded::class.java).name.assert().isEqualTo("shop.cart.ItemPut")
        model.mountedCommandTypes.single().name.assert().isEqualTo("shop.cart.ItemRemoved")
        CommandTypes.SIMPLE_NAMES.nameOf(model.type, AddItem::class.java).assert().isEqualTo("shop.cart.PutItem")
    }

    @Test
    fun `a name given that disagrees with the type's @Name fails, saying how to fix it`() {
        val command = assertThrows<IllegalArgumentException> {
            aggregate<Cart>("shop", "cart") {
                initial { Cart(it.id) }
                decide<AddItem>(name = "shop.cart.AddItem") { _, _ -> Decision.NoOp }
            }
        }
        command.message.assert().contains("shop.cart.PutItem").contains("leave the name out")
        assertThrows<IllegalArgumentException> {
            aggregate<Cart>("shop", "cart") {
                initial { Cart(it.id) }
                evolve<ItemAdded>(name = "shop.cart.ItemAdded") { }
            }
        }.message.assert().contains("shop.cart.ItemPut")
        assertThrows<IllegalArgumentException> {
            aggregate<Cart>("shop", "cart") {
                initial { Cart(it.id) }
                mount<AddItem>(name = "shop.cart.Load")
            }
        }
        val agreed = aggregate<Cart>("shop", "cart") {
            initial { Cart(it.id) }
            evolve<ItemAdded>(name = "shop.cart.ItemPut") { }
            evolve<ItemRemoved>(name = "shop.cart.ItemDropped") { }
        }
        agreed.eventType(ItemAdded::class.java).name.assert().isEqualTo("shop.cart.ItemPut")
        agreed.eventType(ItemRemoved::class.java).name.assert().isEqualTo("shop.cart.ItemDropped")
    }

    @Test
    fun `an aggregate named otherwise than its annotated state class fails, saying which name to use`() {
        aggregate<ShoppingCart>("shop", "shopping_cart") { initial { ShoppingCart(it.id) } }
            .type.name.assert().isEqualTo("shopping_cart")
        assertThrows<IllegalArgumentException> {
            aggregate<ShoppingCart>("shop", "ShoppingCart") { initial { ShoppingCart(it.id) } }
        }.message.assert().contains("name the aggregate shopping_cart")
        aggregate<NamedCart>("shop", "basket") { initial { NamedCart(it.id) } }
        assertThrows<IllegalArgumentException> {
            aggregate<NamedCart>("shop", "named_cart") { initial { NamedCart(it.id) } }
        }.message.assert().contains("name the aggregate basket")
    }
}
