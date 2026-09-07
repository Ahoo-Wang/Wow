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

package me.ahoo.wow.saga.stateless

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.testFunction
import me.ahoo.wow.command.wait.testSignal
import me.ahoo.wow.command.wait.thenNotifyAndForget
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.SimpleStateEventExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException

class StatelessSagaRetryTest {
    @ParameterizedTest
    @CsvSource("SENT, false", "PROCESSED, false", "SNAPSHOT, false", "PROJECTED, false", "PROJECTED, true")
    @Suppress("LongMethod")
    fun `chain matches persisted requests after saga and exchange are recreated`(
        tailStage: CommandStage,
        projectionFails: Boolean
    ) {
        val event = fixtureEvent()
        val endpoint = SimpleCommandWaitEndpoint("request-retry")
        val coordinator = DefaultWaitCoordinator()
        val notifier = object : CommandWaitNotifier {
            override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> =
                Mono.fromRunnable { coordinator.signal(waitSignal) }
        }
        val store = InMemoryEventStore()
        val accepted = mutableListOf<CommandMessage<*>>()
        val projectionFunction = testFunction(
            contextName = "projection",
            processorName = "projection",
            name = "onEvent"
        )
        var secondAttempts = 0
        val bus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.defer {
                if (message.aggregateId.id == "second" && ++secondAttempts == 1) {
                    return@defer Mono.error(TimeoutException("temporary send failure"))
                }
                val stream = MockAggregateCreated("stored").toDomainEventStream(message)
                val domainEvent = stream.first().toJsonString().toObject<DomainEvent<*>>()
                val projection = SimpleDomainEventExchange(domainEvent).setFunction(projectionFunction)
                val projectionResult = if (projectionFails && message.aggregateId.id == "first") {
                    Mono.error<Void>(IllegalStateException("projection failed"))
                } else {
                    Mono.empty()
                }
                store.append(stream)
                    .doOnSuccess { accepted += message }
                    .then(
                        Mono.empty<Void>().thenNotifyAndForget(
                            notifier,
                            CommandStage.PROCESSED,
                            SimpleServerCommandExchange(message)
                        )
                    )
                    .then(
                        Mono.empty<Void>().thenNotifyAndForget(
                            notifier,
                            CommandStage.SNAPSHOT,
                            SimpleStateEventExchange(stream.toStateEvent("state"))
                        )
                    )
                    .then(
                        projectionResult.thenNotifyAndForget(
                            notifier,
                            CommandStage.PROJECTED,
                            projection
                        ).onErrorComplete()
                    )
            }

            override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
        }
        val gateway = DefaultCommandGateway(
            endpoint,
            bus,
            NoOpValidator,
            DefaultRequestIdChecker(AggregateIdempotencyCheckerProvider { IdempotencyChecker { false } }, store),
            coordinator,
            notifier,
        )
        val delegate = StubMessageFunction(
            Mono.just(listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two")))
        )
        val plan = CommandWait.chain(
            event.commandId,
            NamedFunctionInfoData(delegate.contextName, delegate.processorName, delegate.name),
            tailStage.toWaitingChainTail(NamedFunctionInfoData("projection", "projection", "onEvent")),
        )
        plan.propagate(endpoint, event.header)
        val handle = coordinator.createLast(plan)
        coordinator.signal(testSignal(stage = CommandStage.PROCESSED, waitCommandId = event.commandId))
        var completedExchange: DomainEventExchange<*>? = null
        val handling = Mono.defer {
            val saga = StatelessSagaFunction(
                delegate,
                gateway,
                SimpleCommandMessageFactory(NoOpValidator, SimpleCommandBuilderRewriterRegistry())
            )
            val exchange = SimpleDomainEventExchange(event).setFunction(saga)
            saga.invoke(exchange).doOnNext { completedExchange = exchange }
        }.retry(1).doOnNext { stream ->
            stream.size.assert().isEqualTo(2)
            stream.first().commandId.assert().isNotEqualTo(accepted.first().commandId)
        }.then(
            Mono.defer {
                Mono.empty<Void>().thenNotifyAndForget(
                    notifier,
                    CommandStage.SAGA_HANDLED,
                    requireNotNull(completedExchange)
                )
            }
        )
        try {
            StepVerifier.create(handling).verifyComplete()
            StepVerifier.create(handle.await())
                .assertNext { signal ->
                    signal.succeeded.assert().isEqualTo(!projectionFails)
                    if (projectionFails) signal.errorMsg.assert().isEqualTo("projection failed")
                }
                .expectComplete().verify(Duration.ofSeconds(2))
            accepted.map { it.aggregateId.id }.assert().containsExactly("first", "second")
            secondAttempts.assert().isEqualTo(2)
        } finally {
            handle.cancel()
        }
    }

    @Test
    fun `retry sends all commands through gateway with stable request ids`() {
        val attempts = mutableListOf<CommandMessage<*>>()
        var secondAttempts = 0
        val gateway = mockk<CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                val command = firstArg<CommandMessage<*>>()
                attempts += command
                if (command.aggregateId.id == "second" && secondAttempts++ == 0) {
                    Mono.error(TimeoutException("temporary send failure"))
                } else {
                    Mono.empty()
                }
            }
        }
        val saga = StatelessSagaFunction(
            StubMessageFunction(
                Mono.just(listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two")))
            ),
            gateway,
            commandMessageFactory(),
        )
        val event = fixtureEvent()
        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event)).retry(1))
            .assertNext { it.size.assert().isEqualTo(2) }
            .expectComplete().verify(Duration.ofSeconds(5))
        attempts.map { it.aggregateId.id }.assert().containsExactly("first", "second", "first", "second")
        attempts.map { it.requestId }.assert().containsExactly(
            "${event.id}-0",
            "${event.id}-1",
            "${event.id}-0",
            "${event.id}-1"
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `wait chain receives the final retry outcome`(terminalFailure: Boolean) {
        val event = fixtureEvent()
        val endpoint = SimpleCommandWaitEndpoint("review")
        val coordinator = DefaultWaitCoordinator()
        val notifier = object : CommandWaitNotifier {
            override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> =
                Mono.fromRunnable { coordinator.signal(waitSignal) }
        }
        var secondAttempts = 0
        val bus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.defer {
                if (message.aggregateId.id == "second" && (++secondAttempts == 1 || terminalFailure)) {
                    Mono.error(TimeoutException("send failed"))
                } else {
                    Mono.fromRunnable {
                        coordinator.signal(
                            testSignal(
                                stage = CommandStage.PROCESSED,
                                waitCommandId = event.commandId,
                                commandId = message.commandId,
                                function = testFunction(
                                    contextName = "projection",
                                    processorName = "projection",
                                    name = "onEvent"
                                ),
                            )
                        )
                    }
                }
            }
            override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
        }
        val gateway = DefaultCommandGateway(
            endpoint,
            bus,
            NoOpValidator,
            RequestIdChecker { _, _ -> Mono.just(true) },
            coordinator,
            notifier
        )
        val saga = StatelessSagaFunction(
            StubMessageFunction(
                Mono.just(listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two")))
            ),
            gateway,
            commandMessageFactory(),
        )
        val plan = CommandWait.chain(
            event.commandId,
            NamedFunctionInfoData(saga.contextName, saga.processorName, saga.name),
            CommandStage.PROCESSED.toWaitingChainTail(NamedFunctionInfoData("projection", "projection", "onEvent")),
        )
        plan.propagate(endpoint, event.header)
        val handle = coordinator.createLast(plan)
        coordinator.signal(
            testSignal(stage = CommandStage.PROCESSED, waitCommandId = event.commandId, commandId = event.commandId)
        )
        val exchange = SimpleDomainEventExchange(event).setFunction(saga)
        val handling = saga.invoke(exchange).retry(1).then()
            .thenNotifyAndForget(notifier, CommandStage.SAGA_HANDLED, exchange)
        if (terminalFailure) {
            StepVerifier.create(handling).expectError(TimeoutException::class.java).verify()
        } else {
            StepVerifier.create(handling).verifyComplete()
        }
        StepVerifier.create(handle.await())
            .assertNext { it.succeeded.assert().isEqualTo(!terminalFailure) }
            .expectComplete().verify(Duration.ofSeconds(5))
    }
}
