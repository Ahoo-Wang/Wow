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
import jakarta.validation.Path
import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.tck.command.CommandGatewaySpec
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class DefaultCommandGatewayTest : CommandGatewaySpec() {
    override fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    @Test
    fun sendWithSend() {
        val messageGateway = createMessageBus()
        val commandMessage: CommandMessage<Any> = mockk()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            messageGateway.send(commandMessage, WaitingFor.stage(CommandStage.SENT, "", ""))
        }
    }

    @Test
    fun validateError() {
        val path = mockk<Path>()
        every { path.toString() } returns "propertyPath"
        val constraintViolations = setOf(
            mockk<jakarta.validation.ConstraintViolation<Any>> {
                every { propertyPath } returns path
                every { message } returns "message"
            }
        )
        val validator = mockk<Validator> {
            every { validate<Any>(any()) } returns constraintViolations
        }
        val messageGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = createCommandBus(),
            idempotencyChecker = idempotencyChecker,
            waitStrategyRegistrar = waitStrategyRegistrar,
            validator,
        )
        val commandMessage: CommandMessage<MockCreateCommand> =
            MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage()
        Assertions.assertThrows(CommandValidationException::class.java) {
            messageGateway.send(commandMessage).block()
        }
    }
}
