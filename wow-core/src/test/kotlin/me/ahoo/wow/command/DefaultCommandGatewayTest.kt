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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.tck.command.CommandGatewaySpec
import me.ahoo.wow.tck.mock.MockVoidCommand
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class DefaultCommandGatewayTest : CommandGatewaySpec() {
    override fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    @Test
    fun sendWithSent() {
        val messageGateway = createMessageBus()
        val commandMessage: CommandMessage<Any> = mockk()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            messageGateway.send(commandMessage, WaitingFor.stage(CommandStage.SENT, "", ""))
        }
    }

    @Test
    fun sendVoidCommand() {
        val messageGateway = createMessageBus()
        val commandMessage = MockVoidCommand(generateGlobalId()).toCommandMessage()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            messageGateway.send(commandMessage, WaitingFor.stage(CommandStage.PROCESSED, "", ""))
        }
    }

    @Test
    fun validateCommandBody() {
        val messageGateway = createMessageBus()
        val commandMessage: CommandMessage<CommandValidator> = mockk {
            every { body } returns MockCommandBody()
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            messageGateway.send(commandMessage, WaitingFor.stage(CommandStage.SENT, "", ""))
        }
    }

    class MockCommandBody : CommandValidator {
        override fun validate() {
            throw CommandValidationException(this)
        }
    }
}
