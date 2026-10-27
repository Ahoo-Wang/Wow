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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.MountedCommand
import me.ahoo.wow.api.annotation.Name
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A transformer names its command types from the aggregate and the class, as the model names them. */
class CommandTypeOfTest {
    data class AddItem(val sku: String)

    @Name("Remove")
    data class RemoveItem(val sku: String)

    @MountedCommand(aggregate = "cart")
    data class ImportCart(val id: String)

    private val cart = AggregateType("shop", "cart")

    @Test
    fun `a command type is named after its aggregate and its class, its @Name first`() {
        CommandType.of(
            cart,
            AddItem::class.java
        ).assert().isEqualTo(CommandType.of("shop.cart.AddItem", AddItem::class.java))
        CommandType.of(cart, RemoveItem::class.java).name.assert().isEqualTo("shop.cart.Remove")
        CommandType.of(cart, ImportCart::class.java).name.assert().isEqualTo("shop.cart.ImportCart")
    }

    @Test
    fun `a mounted command is named under the aggregate it is mounted under alone`() {
        val order = AggregateType("shop", "order")
        val misplaced = assertThrows<IllegalArgumentException> { CommandType.of(order, ImportCart::class.java) }
        misplaced.message.assert().isEqualTo(
            "${ImportCart::class.java.name} is mounted under cart, not under shop.order."
        )
    }
}
