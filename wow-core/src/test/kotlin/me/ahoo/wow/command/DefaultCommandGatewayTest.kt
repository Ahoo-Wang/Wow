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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.tck.command.CommandGatewaySpec
import me.ahoo.wow.tck.mock.MockVoidCommand
import me.ahoo.wow.test.validation.TestValidator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

internal class DefaultCommandGatewayTest : CommandGatewaySpec() {
    override fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    @Test
    fun sendVoidCommand() {
        val messageGateway = createMessageBus()
        val message = MockVoidCommand(generateGlobalId()).toCommandMessage()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            messageGateway.send(message, WaitingForStage.stage(CommandStage.PROCESSED, "", ""))
        }
    }

    @Test
    fun validateCommandBody() {
        val message = MockCommandBody().toCommandMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .expectError(CommandResultException::class.java)
                .verify()
        }
        Thread.sleep(5)
        waitStrategyRegistrar.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun validateCancel() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify()
        }
        Thread.sleep(5)
        waitStrategyRegistrar.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun validateCommandBodyWhenValidateError() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns IllegalArgumentException().toMono()
        }
        val commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitStrategyRegistrar = waitStrategyRegistrar,
            commandWaitNotifier = LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        )
        val message = createMessage()
        commandGateway.sendAndWaitForProcessed(message)
            .test()
            .expectError(CommandResultException::class.java)
            .verify()
        Thread.sleep(10)
        waitStrategyRegistrar.contains(message.commandId).assert().isFalse()
    }

    class MockCommandBody : CommandValidator {
        override fun validate() {
            throw CommandValidationException(this)
        }
    }
}
