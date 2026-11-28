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
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.read.EventStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * D484 ⑧: a model written by hand takes no name the compiler would refuse (`NameRules`): the aggregate's, and the
 * last part of a command's or an event's, so the log holds no name a topic, a route or a generated contract cannot
 * carry. Before, the DSL took any text.
 */
class ModelNameRuleTest {
    class Cart(val id: String)
    data class AddItem(val sku: String)
    data class ItemAdded(val sku: String)

    @Name("Put Item")
    data class Misnamed(val sku: String)

    private val rule = "is not a name: a name starts with a letter and holds only letters, digits, '_' and '-' " +
        "([A-Za-z][A-Za-z0-9_-]*), such as order-item; a dot separates a context, an aggregate and a type, and the " +
        "generated contracts take an identifier from every name"

    private fun refused(block: () -> Unit): String = assertThrows<IllegalArgumentException>(block).message!!

    @Test
    fun `an aggregate whose name is no name is refused, saying the rule`() {
        // A blank name is no aggregate type at all (AggregateType refuses it).
        listOf("order item", "1st", "a.b", "a/b", "-").forEach { name ->
            refused { aggregate<Cart>("shop", name) { initial { Cart(it.id) } } }.assert().describedAs(name)
                .isEqualTo("The aggregate shop.$name is named \"$name\", which $rule. Give it a name of that form.")
        }
    }

    @Test
    fun `a command or an event whose name ends in no name is refused, naming its class`() {
        val type = AddItem::class.java.name
        refused {
            aggregate<Cart>("shop", "cart") { decide<AddItem>(name = "shop.cart.Add Item") { _, _ -> Decision.NoOp } }
        }.assert().isEqualTo(
            "shop.cart names the command $type \"shop.cart.Add Item\", whose last part $rule. Give it a name of " +
                "that form.",
        )
        refused {
            aggregate<Cart>("shop", "cart") { decide<AddItem>(name = "shop.cart.") { _, _ -> Decision.NoOp } }
        }.assert().contains("names the command $type \"shop.cart.\", whose last part is not a name")
        refused { aggregate<Cart>("shop", "cart") { mount<AddItem>(name = "1st") } }
            .assert().contains("names the command $type \"1st\", whose last part is not a name")
        refused { aggregate<Cart>("shop", "cart") { evolve<ItemAdded>(name = "shop.cart.Item-Added!") { } } }
            .assert().contains("names the event ${ItemAdded::class.java.name} \"shop.cart.Item-Added!\"")
        refused { aggregate<Cart>("shop", "cart") { evolve<ItemAdded>(name = "") { _: ItemAdded, _: EventStream -> } } }
            .assert().contains("names the event ${ItemAdded::class.java.name} \"\"")
        // A class whose own @Name is none gives a default name that is refused the same.
        refused { aggregate<Cart>("shop", "cart") { evolve<Misnamed> { } } }
            .assert().contains("\"shop.cart.Put Item\", whose last part is not a name")
    }

    @Test
    fun `a name of the rule's form, and the framework's own, are taken`() {
        val model = aggregate<Cart>("shop", "shopping_cart") {
            initial { Cart(it.id) }
            decide<AddItem>(name = "shop.shopping_cart.Add-Item_2") { _, body -> Decision.events(ItemAdded(body.sku)) }
            decide<DeleteAggregate> { _, _ -> Decision.NoOp }
            evolve<ItemAdded> { }
        }
        model.commandTypes.map { it.name }.assert()
            .contains("shop.shopping_cart.Add-Item_2", "wow.lifecycle.DeleteAggregate")
        model.eventType(ItemAdded::class.java).name.assert().isEqualTo("shop.shopping_cart.ItemAdded")
    }
}
