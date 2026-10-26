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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.CancelSchedule
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What a model tells about the commands it decides (decisions D55, D108). */
class AggregateModelTest {
    class Cart(val id: String)

    data class AddItem(val sku: String)

    data class RemoveItem(val sku: String)

    data class ItemAdded(val sku: String)

    @Test
    fun `a model lists the command types it decides, the kernel's lifecycle commands after its own`() {
        val model = aggregate<Cart>("shop", "cart") {
            initial { Cart(it.id) }
            decide<AddItem>(Creation.ALLOWED) { _, body -> Decision.events(ItemAdded(body.sku)) }
            decide<RemoveItem>(name = "shop.cart.DropItem") { _, _ -> Decision.NoOp }
            alias<AddItem>("shop.cart.PutItem")
            evolve<ItemAdded> { }
            lifecycle(DeleteAggregate::class.java)
        }
        model.commandTypes.assert().containsExactly(
            CommandType("shop.cart.AddItem", AddItem::class.java, setOf("shop.cart.PutItem")),
            CommandType("shop.cart.DropItem", RemoveItem::class.java),
            CommandType(Lifecycle.DELETE_AGGREGATE, DeleteAggregate::class.java),
            CommandType(Schedules.SET, SetSchedule::class.java),
            CommandType(Schedules.CANCEL, CancelSchedule::class.java),
        )
        model.commandTypes.forEach { model.decider(it.type).assert().isNotNull() }
    }

    data class ImportCart(val legacy: String)

    @Test
    fun `a model's state revision is 1 unless it raises it, and its snapshots carry it (D172)`() {
        val plain = aggregate<Cart>("shop", "cart") { initial { Cart(it.id) } }
        plain.stateRevision.assert().isEqualTo(1)
        val raised = aggregate<Cart>("shop", "cart") {
            initial { Cart(it.id) }
            stateRevision(3)
        }
        raised.stateRevision.assert().isEqualTo(3)
        val ref = raised.type.ref("tenant", "c-1")
        EventCodec().snapshotOf(raised, ref, Cart("c-1"), 1, Scope.NONE, 0, 0)
            .stateRevision.assert().isEqualTo(3)
        assertThrows<IllegalArgumentException> {
            aggregate<Cart>("shop", "cart") {
                initial { Cart(it.id) }
                stateRevision(0)
            }
        }
    }

    @Test
    fun `a model finds the commands mounted under it apart from those it decides (D182)`() {
        val model = aggregate<Cart>("shop", "cart") {
            initial { Cart(it.id) }
            decide<AddItem> { _, body -> Decision.events(ItemAdded(body.sku)) }
            mount<ImportCart>()
            mount<RemoveItem>(name = "shop.cart.Drop")
            alias<ImportCart>("shop.cart.Load")
            evolve<ItemAdded> { }
        }
        model.mountedCommandTypes.map { it.name }.assert().containsExactly("shop.cart.ImportCart", "shop.cart.Drop")
        model.findMountedCommandType("shop.cart.Load")!!.type.assert().isEqualTo(ImportCart::class.java)
        model.findMountedCommandType("shop.cart.AddItem").assert().isNull()
        model.findCommandType("shop.cart.ImportCart").assert().isNull()
        model.decider(ImportCart::class.java).assert().isNull()
    }

    @Test
    fun `a mounted command is mounted once, is not decided, and takes no name of another command`() {
        listOf<AggregateModelBuilder<Cart>.() -> Unit>(
            {
                mount<ImportCart>()
                mount<ImportCart>(name = "shop.cart.Other")
            },
            { mount<AddItem>() },
            { mount<ImportCart>(name = "shop.cart.AddItem") },
            { mount<ImportCart>(name = "${Lifecycle.NAMESPACE}.Import") },
        ).forEach { configure ->
            runCatching {
                aggregate<Cart>("shop", "cart") {
                    initial { Cart(it.id) }
                    decide<AddItem> { _, body -> Decision.events(ItemAdded(body.sku)) }
                    evolve<ItemAdded> { }
                    configure()
                }
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
