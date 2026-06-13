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
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.command.wait.testSignal
import me.ahoo.wow.command.wait.waitStrategyHeader
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import org.junit.jupiter.api.Test
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
    fun `send and wait stream propagates header creates handle and emits results in order`() {
        val commandBus = RecordingCommandBus()
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(commandBus = commandBus, waitCoordinator = waitCoordinator)
        val waitPlan = CommandWait.processed("wait-command-id")
        val command = TestCommandMessage(id = "command-id")

        StepVerifier.create(gateway.sendAndWaitStream(command, waitPlan))
            .assertNext {
                it.stage.assert().isEqualTo(CommandStage.SENT)
                it.commandId.assert().isEqualTo("command-id")
            }
            .then {
                waitCoordinator.signal(
                    testSignal(
                        CommandStage.PROCESSED,
                        waitCommandId = "wait-command-id",
                        commandId = "command-id",
                        signalTime = 2,
                    )
                ).assert().isTrue()
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .verifyComplete()

        commandBus.sent.single().assert().isSameAs(command)
        command.header["command_wait_endpoint"].assert().isEqualTo("test-command-wait-endpoint")
        waitCoordinator.contains("wait-command-id").assert().isFalse()
    }

    @Test
    fun `send and wait returns last result`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)
        val command = TestCommandMessage(id = "command-id")
        val waitPlan = CommandWait.processed("wait-command-id")

        StepVerifier.create(gateway.sendAndWait(command, waitPlan))
            .then {
                waitCoordinator.signal(
                    testSignal(
                        CommandStage.PROCESSED,
                        waitCommandId = "wait-command-id",
                        commandId = "command-id",
                    )
                ).assert().isTrue()
            }
            .assertNext {
                it.stage.assert().isEqualTo(CommandStage.PROCESSED)
                it.waitCommandId.assert().isEqualTo("wait-command-id")
            }
            .verifyComplete()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
    }

    @Test
    fun `send and wait stream does not create handle or send command before subscription`() {
        val commandBus = RecordingCommandBus()
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(commandBus = commandBus, waitCoordinator = waitCoordinator)

        gateway.sendAndWaitStream(
            TestCommandMessage(id = "command-id"),
            CommandWait.processed("wait-command-id"),
        )

        waitCoordinator.contains("wait-command-id").assert().isFalse()
        commandBus.sent.assert().isEmpty()
    }

    @Test
    fun `send and wait does not create handle or send command before subscription`() {
        val commandBus = RecordingCommandBus()
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(commandBus = commandBus, waitCoordinator = waitCoordinator)

        gateway.sendAndWait(
            TestCommandMessage(id = "command-id"),
            CommandWait.processed("wait-command-id"),
        )

        waitCoordinator.contains("wait-command-id").assert().isFalse()
        commandBus.sent.assert().isEmpty()
    }

    @Test
    fun `duplicate wait id is reported as reactive error on subscription`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)
        val activeHandle = waitCoordinator.createLast(CommandWait.processed("wait-command-id"))

        val result = gateway.sendAndWait(
            TestCommandMessage(id = "command-id"),
            CommandWait.processed("wait-command-id"),
        )

        StepVerifier.create(result)
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                it.message.assert().isEqualTo("Wait handle already registered for waitCommandId[wait-command-id].")
            }
            .verify()

        activeHandle.cancel()
        waitCoordinator.contains("wait-command-id").assert().isFalse()
    }

    @Test
    fun `send and wait stream maps command bus errors to command result exception and unregisters`() {
        val commandBus = RecordingCommandBus().apply {
            sendResult = { Mono.error(IllegalStateException("bus failed")) }
        }
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(commandBus = commandBus, waitCoordinator = waitCoordinator)
        val waitPlan = CommandWait.processed("wait-command-id")

        StepVerifier.create(gateway.sendAndWaitStream(TestCommandMessage(id = "command-id"), waitPlan))
            .expectErrorSatisfies {
                it.assert().isInstanceOf(CommandResultException::class.java)
                val result = (it as CommandResultException).commandResult
                result.waitCommandId.assert().isEqualTo("wait-command-id")
                result.stage.assert().isEqualTo(CommandStage.SENT)
                result.succeeded.assert().isFalse()
            }
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
        waitCoordinator.createStream(CommandWait.processed("wait-command-id")).cancel()
    }

    @Test
    fun `void command requires a wait plan that supports void commands and releases handle`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)

        StepVerifier.create(
            gateway.sendAndWaitStream(
                TestCommandMessage(id = "void-command", isVoid = true),
                CommandWait.processed("wait-command-id"),
            )
        )
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                it.message.assert().contains("must support void command")
            }
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
        waitCoordinator.createStream(CommandWait.processed("wait-command-id")).cancel()
    }

    @Test
    fun `send and wait stream cancellation releases handle and allows wait id reuse`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)

        StepVerifier.create(
            gateway.sendAndWaitStream(
                TestCommandMessage(id = "command-id"),
                CommandWait.processed("wait-command-id"),
            )
        )
            .expectNextCount(1)
            .thenCancel()
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()

        StepVerifier.create(
            gateway.sendAndWaitStream(
                TestCommandMessage(id = "another-command-id"),
                CommandWait.processed("wait-command-id"),
            )
        )
            .expectNextCount(1)
            .thenCancel()
            .verify()
    }

    private fun commandGateway(
        commandBus: RecordingCommandBus = RecordingCommandBus(),
        idempotencyChecker: IdempotencyChecker = NoOpIdempotencyChecker,
        waitCoordinator: WaitCoordinator = DefaultWaitCoordinator(),
        notifier: RecordingCommandWaitNotifier = RecordingCommandWaitNotifier(),
    ): DefaultCommandGateway =
        DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("test-command-wait-endpoint"),
            commandBus = commandBus,
            validator = NoOpValidator,
            idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitCoordinator = waitCoordinator,
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
