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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.RecordingWaitStrategyRegistrar
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.command.wait.waitStrategyHeader
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DefaultCommandGatewayTest {

    @Test
    fun `send rejects duplicate request before delegating to command bus`() {
        val commandBus = RecordingCommandBus()
        val gateway = commandGateway(
            commandBus = commandBus,
            idempotencyChecker = IdempotencyChecker { Mono.just(false) },
        )

        StepVerifier.create(gateway.send(TestCommandMessage(id = "duplicate-command")))
            .expectError(DuplicateRequestIdException::class.java)
            .verify()

        commandBus.sent.assert().isEmpty()
    }

    @Test
    fun `send notifies extracted wait strategy after command is sent`() {
        val notifier = RecordingCommandWaitNotifier()
        val gateway = commandGateway(notifier = notifier)
        val command = TestCommandMessage(
            id = "command-id",
            header = waitStrategyHeader(
                waitCommandId = "wait-command-id",
                endpoint = "wait-endpoint",
                stage = CommandStage.PROCESSED,
            ),
        )

        StepVerifier.create(gateway.send(command))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        val notification = notifier.notifications.single()
        notification.endpoint.assert().isEqualTo("wait-endpoint")
        notification.signal.stage.assert().isEqualTo(CommandStage.SENT)
        notification.signal.waitCommandId.assert().isEqualTo("wait-command-id")
        notification.signal.commandId.assert().isEqualTo("command-id")
        notification.signal.errorCode.assert().isEqualTo("Ok")
    }

    @Test
    fun `send with wait strategy propagates header registers strategy and returns exchange`() {
        val commandBus = RecordingCommandBus()
        val registrar = RecordingWaitStrategyRegistrar()
        val gateway = commandGateway(commandBus = commandBus, registrar = registrar)
        val waitStrategy = WaitingForStage.processed("wait-command-id")
        val command = TestCommandMessage(id = "command-id")

        StepVerifier.create(gateway.send(command, waitStrategy))
            .assertNext { exchange ->
                exchange.message.assert().isSameAs(command)
                exchange.waitStrategy.assert().isSameAs(waitStrategy)
            }
            .verifyComplete()

        commandBus.sent.single().assert().isSameAs(command)
        command.header["command_wait_endpoint"].assert().isEqualTo("test-command-wait-endpoint")
        registrar.get("wait-command-id").assert().isSameAs(waitStrategy)
    }

    @Test
    fun `send with wait strategy maps command bus errors to command result exception and unregisters`() {
        val commandBus = RecordingCommandBus().apply {
            sendResult = { Mono.error(IllegalStateException("bus failed")) }
        }
        val registrar = RecordingWaitStrategyRegistrar()
        val gateway = commandGateway(commandBus = commandBus, registrar = registrar)
        val waitStrategy = WaitingForStage.processed("wait-command-id")

        StepVerifier.create(gateway.send(TestCommandMessage(id = "command-id"), waitStrategy))
            .expectError(CommandResultException::class.java)
            .verify()

        registrar.contains("wait-command-id").assert().isFalse()
    }

    @Test
    fun `void command requires a wait strategy that supports void commands`() {
        val gateway = commandGateway()

        val exception = assertThrows<IllegalArgumentException> {
            gateway.send(
                TestCommandMessage(id = "void-command", isVoid = true),
                WaitingForStage.processed("wait-command-id"),
            )
        }

        exception.message.assert().contains("must support void command")
    }

    private fun commandGateway(
        commandBus: RecordingCommandBus = RecordingCommandBus(),
        idempotencyChecker: IdempotencyChecker = NoOpIdempotencyChecker,
        registrar: RecordingWaitStrategyRegistrar = RecordingWaitStrategyRegistrar(),
        notifier: RecordingCommandWaitNotifier = RecordingCommandWaitNotifier(),
    ): DefaultCommandGateway =
        DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("test-command-wait-endpoint"),
            commandBus = commandBus,
            validator = NoOpValidator,
            idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitStrategyRegistrar = registrar,
            commandWaitNotifier = notifier,
        )
}

private class RecordingCommandBus : CommandBus {
    val sent: MutableList<CommandMessage<*>> = mutableListOf()
    var sendResult: (CommandMessage<*>) -> Mono<Void> = { Mono.empty() }

    override fun send(message: CommandMessage<*>): Mono<Void> =
        Mono.defer {
            sent += message
            sendResult(message)
        }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> = Flux.empty()
}
