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

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.testAggregateId
import me.ahoo.wow.eventsourcing.NoopRequestIdExistenceChecker
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gateway's request-ID reservation (B7): validation comes first, and a failed send gives the reservation back.
 */
class DefaultCommandGatewayRequestIdTest {
    private val gateways = mutableListOf<DefaultCommandGateway>()

    @AfterEach
    fun closeGateways() {
        gateways.forEach(DefaultCommandGateway::close)
    }

    @Test
    fun `send validates the command before reserving its request id`() {
        val reserved = mutableListOf<String>()
        val commandBus = FailingFirstCommandBus(failures = 0)
        val gateway = commandGateway(
            commandBus = commandBus,
            idempotencyChecker = IdempotencyChecker {
                reserved += it
                true
            },
        )
        val command = SimpleCommandMessage(body = InvalidCommand, aggregateId = testAggregateId())

        StepVerifier.create(gateway.send(command))
            .expectError(IllegalArgumentException::class.java)
            .verify()

        reserved.assert().isEmpty()
        commandBus.sent.assert().isEmpty()
    }

    @Test
    fun `send releases the request id reservation when the command bus fails`() {
        val existenceChecks = AtomicInteger()
        val commandBus = FailingFirstCommandBus()
        val gateway = commandGateway(
            commandBus = commandBus,
            idempotencyChecker = bloomFilterIdempotencyChecker(),
            requestIdExistenceChecker = countingExistenceChecker(existenceChecks),
        )
        val command = TestCommandMessage(id = "send-failed-command")

        StepVerifier.create(gateway.send(command))
            .expectErrorMatches { it === commandBus.failure }
            .verify()
        StepVerifier.create(gateway.send(command))
            .verifyComplete()

        existenceChecks.get().assert().isZero()
        commandBus.sent.assert().containsExactly(command, command)
    }

    @Test
    fun `send and wait releases the request id reservation when the command bus fails`() {
        val existenceChecks = AtomicInteger()
        val commandBus = FailingFirstCommandBus()
        val gateway = commandGateway(
            commandBus = commandBus,
            idempotencyChecker = bloomFilterIdempotencyChecker(),
            requestIdExistenceChecker = countingExistenceChecker(existenceChecks),
        )
        val command = TestCommandMessage(id = "send-and-wait-failed-command")

        StepVerifier.create(gateway.sendAndWait(command, CommandWait.sent(command.commandId)))
            .expectError(CommandResultException::class.java)
            .verify()
        StepVerifier.create(gateway.sendAndWaitForSent(command))
            .expectNextCount(1)
            .verifyComplete()

        existenceChecks.get().assert().isZero()
    }

    @Test
    fun `send keeps the request id reservation when the command is sent`() {
        val existenceChecks = AtomicInteger()
        val gateway = commandGateway(
            commandBus = FailingFirstCommandBus(failures = 0),
            idempotencyChecker = bloomFilterIdempotencyChecker(),
            requestIdExistenceChecker = countingExistenceChecker(existenceChecks),
        )
        val command = TestCommandMessage(id = "sent-command")

        StepVerifier.create(gateway.send(command))
            .verifyComplete()
        StepVerifier.create(gateway.send(command))
            .expectError(DuplicateRequestIdException::class.java)
            .verify()

        existenceChecks.get().assert().isEqualTo(1)
    }

    private fun countingExistenceChecker(existenceChecks: AtomicInteger) =
        RequestIdExistenceChecker { _, _ ->
            existenceChecks.incrementAndGet()
            Mono.just(true)
        }

    private fun bloomFilterIdempotencyChecker(): IdempotencyChecker =
        BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 1000)
        }

    private fun commandGateway(
        commandBus: CommandBus,
        idempotencyChecker: IdempotencyChecker,
        requestIdExistenceChecker: RequestIdExistenceChecker = NoopRequestIdExistenceChecker,
    ): DefaultCommandGateway {
        val waitCoordinator = DefaultWaitCoordinator()
        return DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("test-command-wait-endpoint"),
            commandBus = commandBus,
            validator = NoOpValidator,
            requestIdChecker = DefaultRequestIdChecker(
                idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { idempotencyChecker },
                requestIdExistenceChecker = requestIdExistenceChecker,
            ),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = RecordingCommandWaitNotifier(),
        ).also { gateways += it }
    }
}

private object InvalidCommand : CommandValidator {
    override fun validate() {
        throw IllegalArgumentException("invalid command")
    }
}

/** Fails the first [failures] sends, then accepts. */
private class FailingFirstCommandBus(private val failures: Int = 1) : CommandBus {
    val failure = IllegalStateException("bus unavailable")
    val sent: MutableList<CommandMessage<*>> = mutableListOf()

    override fun send(message: CommandMessage<*>): Mono<Void> =
        Mono.defer {
            sent += message
            if (sent.size <= failures) Mono.error(failure) else Mono.empty()
        }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        MessageReceiver(Flux.empty())
}
