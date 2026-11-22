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

package me.ahoo.wow.runtime.admission

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.spi.SystemCommandGroups
import org.junit.jupiter.api.Test

/** What an authorizer reads of an action's command: a system one, an operational one, a command of a class. */
class CommandActionTest {
    interface EmployeeOnly

    data class ShipOrder(val orderId: String) : EmployeeOnly

    data class PlaceOrder(val sku: String)

    private fun actionOf(name: String, type: Class<*>, aliases: Set<String> = emptySet()) = CommandAction.of(
        CommandType.of(name, type, aliases),
        AggregateType("shop", "order").ref("t", "o-1"),
        Scope.NONE,
        Creation.NONE,
    )

    @Test
    fun `every system command should be system, and only the operational groups operational`() {
        SystemCommandGroups.Group.entries.forEach { group ->
            val operational = group in SystemCommandGroups.OPERATIONAL.groups
            group.commands.forEach { (name, type) ->
                val action = actionOf(name, type)
                action.isSystem.assert().describedAs(name).isTrue()
                action.isOperational.assert().describedAs(name).isEqualTo(operational)
            }
        }
        // The lifecycle commands are system ones, not operational: an owner deletes its own aggregate.
        SystemCommandGroups.Group.LIFECYCLE.commands.keys.assert().isNotEmpty()
        SystemCommandGroups.Group.LIFECYCLE.assert().isNotIn(SystemCommandGroups.OPERATIONAL.groups)
    }

    @Test
    fun `the ledger's read should be a system and an operational action, by its name`() {
        val read = actionOf(Ledger.READ, Any::class.java)
        read.isSystem.assert().isTrue()
        read.isOperational.assert().isTrue()
    }

    @Test
    fun `a domain command should be neither, whatever its class or name says`() {
        val ship = actionOf("shop.order.ShipOrder", ShipOrder::class.java)
        ship.isSystem.assert().isFalse()
        ship.isOperational.assert().isFalse()
        // A domain command named in the system namespace is still judged by its class.
        actionOf("shop.order.wow.schedule.EnableJob", PlaceOrder::class.java).isOperational.assert().isFalse()
    }

    @Test
    fun `a command should be of its class and of what its class implements, not of another`() {
        val ship = actionOf("shop.order.ShipOrder", ShipOrder::class.java)
        ship.commandIs<ShipOrder>().assert().isTrue()
        ship.commandIs<EmployeeOnly>().assert().isTrue()
        ship.commandIs(ShipOrder::class.java).assert().isTrue()
        ship.commandIs<PlaceOrder>().assert().isFalse()
        actionOf("shop.order.PlaceOrder", PlaceOrder::class.java).commandIs<EmployeeOnly>().assert().isFalse()
    }

    @Test
    fun `a command of a former name should be the same command, its class compared`() {
        val renamed = actionOf("shop.order.ShipOrder", ShipOrder::class.java, aliases = setOf("shop.order.Dispatch"))
        renamed.commandIs<ShipOrder>().assert().isTrue()
        renamed.type.isNamed("shop.order.Dispatch").assert().isTrue()
    }
}
