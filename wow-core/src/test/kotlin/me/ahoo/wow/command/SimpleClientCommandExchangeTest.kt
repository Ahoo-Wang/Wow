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
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class SimpleClientCommandExchangeTest {

    @Test
    fun main() {
        val waitingFor = WaitingForStage.sent()
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val commandExchange = SimpleClientCommandExchange(command, waitingFor)
        commandExchange.message.assert().isEqualTo(command)
        commandExchange.waitStrategy.assert().isEqualTo(waitingFor)
        commandExchange.attributes.assert().isEmpty()
        commandExchange.getAggregateVersion().assert().isNull()
    }
}
