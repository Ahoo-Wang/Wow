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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.stage.WaitingForStage
import org.junit.jupiter.api.Test

class SimpleClientCommandExchangeBehaviorTest {

    @Test
    fun `default exchange creates attribute map lazily`() {
        val message = AccountCommand(id = "account-1").toCommandMessage(id = "command-1")
        val waitStrategy = WaitingForStage.sent(message.commandId)
        val exchange = SimpleClientCommandExchange(message, waitStrategy)

        exchange.eagerAttributeMaps().assert().isEmpty()
        exchange.attributes["key"] = "value"
        exchange.attributes["key"].assert().isEqualTo("value")
    }

    @Test
    fun `should expose message wait strategy and mutable attributes`() {
        val message = AccountCommand(id = "account-1").toCommandMessage(id = "command-1")
        val waitStrategy = WaitingForStage.sent(message.commandId)
        val exchange = SimpleClientCommandExchange(message, waitStrategy)

        exchange.message.assert().isSameAs(message)
        exchange.waitStrategy.assert().isSameAs(waitStrategy)
        exchange.attributes.assert().isEmpty()
        exchange.getAggregateVersion().assert().isNull()

        exchange.setAttribute("key", "value")

        exchange.getAttribute<String>("key").assert().isEqualTo("value")
    }

    private fun Any.eagerAttributeMaps(): List<Map<*, *>> =
        javaClass.declaredFields
            .filter { Map::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(this) as? Map<*, *>
            }
}
