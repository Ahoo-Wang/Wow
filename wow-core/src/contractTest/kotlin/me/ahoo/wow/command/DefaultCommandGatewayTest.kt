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
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.tck.command.CommandGatewaySpec
import me.ahoo.wow.tck.mock.MockVoidCommand
import me.ahoo.wow.test.validation.TestValidator
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

internal class DefaultCommandGatewayTest : CommandGatewaySpec() {
    override fun createCommandBus(): CommandBus {
        return InMemoryCommandBus()
    }

    @Test
    fun `should throw when sending void command with waiting for stage`() {
        val messageGateway = createMessageBus()
        val message = MockVoidCommand(generateGlobalId()).toCommandMessage()
        messageGateway.sendAndWait(message, CommandWait.stage(message.commandId, CommandStage.PROCESSED, "", ""))
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `should return sent stage result when sendAndWaitForSent succeeds`() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .consumeNextWith {
                    it.succeeded.assert().isTrue()
                    it.stage.assert().isEqualTo(CommandStage.SENT)
                    it.waitCommandId.assert().isEqualTo(message.commandId)
                    it.commandId.assert().isEqualTo(message.commandId)
                    it.requestId.assert().isEqualTo(message.requestId)
                    it.aggregateId.assert().isEqualTo(message.aggregateId.id)
                    it.function.assert().isEqualTo(COMMAND_GATEWAY_FUNCTION)
                }
                .verifyComplete()
        }
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should support void command when sendAndWaitForSent`() {
        val messageGateway = createMessageBus()
        val message = MockVoidCommand(generateGlobalId()).toCommandMessage()
        messageGateway.sendAndWaitForSent(message)
            .test()
            .consumeNextWith {
                it.succeeded.assert().isTrue()
                it.stage.assert().isEqualTo(CommandStage.SENT)
            }
            .verifyComplete()
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should wrap command bus error when sendAndWaitForSent`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns IllegalArgumentException().toMono()
        }
        val waitCoordinator = DefaultWaitCoordinator()
        val commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            requestIdChecker = DefaultRequestIdChecker(
                DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            ),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator)
        )
        val message = createMessage()
        commandGateway.sendAndWaitForSent(message)
            .test()
            .consumeErrorWith {
                it.assert().isInstanceOf(CommandResultException::class.java)
                val commandResult = (it as CommandResultException).commandResult
                commandResult.stage.assert().isEqualTo(CommandStage.SENT)
                commandResult.succeeded.assert().isFalse()
            }
            .verify()
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should validate command body`() {
        val message = MockCommandBody().toCommandMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .expectError(CommandResultException::class.java)
                .verify()
        }
        Thread.sleep(5)
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should remove wait handle when cancelled`() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify()
        }
        Thread.sleep(5)
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should handle command validation error from command bus`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns IllegalArgumentException().toMono()
        }
        val waitCoordinator = DefaultWaitCoordinator()
        val commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            requestIdChecker = DefaultRequestIdChecker(
                DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            ),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator)
        )
        val message = createMessage()
        commandGateway.sendAndWaitForProcessed(message)
            .test()
            .thenAwait(Duration.ofMillis(10))
            .expectError(CommandResultException::class.java)
            .verify()
        Thread.sleep(10)
        waitCoordinator.contains(message.commandId).assert().isFalse()
    }

    @Test
    fun `should not send command when idempotency check fails`() {
        val commandBus = mockk<CommandBus> {
            every { send(any()) } returns Mono.empty()
        }
        val waitCoordinator = DefaultWaitCoordinator()
        val commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            validator = TestValidator,
            requestIdChecker = DefaultRequestIdChecker(
                DefaultAggregateIdempotencyCheckerProvider {
                    IdempotencyChecker { false }
                },
            ),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator)
        )

        commandGateway.send(createMessage())
            .test()
            .expectError(DuplicateRequestIdException::class.java)
            .verify()

        verify(exactly = 0) {
            commandBus.send(any())
        }
    }

    class MockCommandBody : CommandValidator {
        override fun validate() {
            throw CommandValidationException(this)
        }
    }
}
