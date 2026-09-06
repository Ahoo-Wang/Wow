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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
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
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.ioc.register
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockVoidCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.TimeoutException

class StatelessSagaRetryTest {
    @Test
    fun `partial send retry can reach the unfinished second command`() {
        val store = InMemoryEventStore()
        val attempts = mutableListOf<CommandMessage<*>>()
        val bus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.defer {
                val target = message.aggregateId.id
                attempts += message
                if (target == "second" && attempts.count { it.aggregateId.id == "second" } == 1) {
                    Mono.error(TimeoutException("temporary send failure"))
                } else {
                    store.append(MockAggregateCreated("stored").toDomainEventStream(message))
                }
            }
            override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
        }
        val seen = mutableSetOf<String>()
        val gateway = DefaultCommandGateway(
            SimpleCommandWaitEndpoint("review"),
            bus,
            NoOpValidator,
            DefaultRequestIdChecker(AggregateIdempotencyCheckerProvider { IdempotencyChecker { seen.add(it) } }, store),
            DefaultWaitCoordinator(),
            object : CommandWaitNotifier {
                override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> = Mono.empty()
            },
        )
        val saga = StatelessSagaFunction(
            StubMessageFunction(
                Mono.just(listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two")))
            ),
            gateway,
            commandMessageFactory(),
        )
        val event = fixtureEvent()
        val provider = SimpleServiceProvider().apply { register<EventStore>(mockk()) }
        val exchange = SimpleDomainEventExchange(event).setServiceProvider(provider)
        val retry = RetryableFilter<DomainEventExchange<*>>(Retry.max(1).filter { it is TimeoutException })
        StepVerifier.create(retry.filter(exchange, FilterChain { saga.invoke(it).then() }))
            .expectComplete().verify(Duration.ofSeconds(5))
        attempts.map { it.aggregateId.id }.assert().containsExactly("first", "second", "second")
        attempts[1].commandId.assert().isEqualTo(attempts[2].commandId)
        attempts[1].requestId.assert().isEqualTo(attempts[2].requestId)
        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event).setServiceProvider(provider)))
            .expectError(DuplicateRequestIdException::class.java).verify()
    }

    @Test
    fun `retry preserves streaming command creation including empty factory results`() {
        val actions = mutableListOf<String>()
        var createSecond = 0
        val factory = object : CommandMessageFactory {
            override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> =
                commandMessageFactory().create<TARGET>(commandBuilder).flatMap { command ->
                    val id = command.aggregateId.id
                    actions += "create:$id"
                    when {
                        id == "skip" -> Mono.empty()
                        id == "second" && createSecond++ == 0 -> Mono.error(TimeoutException("creation failed"))
                        else -> Mono.just(command)
                    }
                }
        }
        val sent = mutableListOf<CommandMessage<*>>()
        val gateway = mockk<me.ahoo.wow.command.CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                val command = firstArg<CommandMessage<*>>()
                actions += "send:${command.aggregateId.id}"
                sent += command
                Mono.empty()
            }
        }
        val saga = StatelessSagaFunction(
            StubMessageFunction(
                Mono.just(
                    listOf(
                        MockChangeAggregate("skip", "zero"),
                        MockChangeAggregate("first", "one"),
                        MockChangeAggregate("second", "two"),
                    )
                )
            ),
            gateway,
            factory,
        )
        val event = fixtureEvent()
        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event)).retry(1))
            .assertNext { stream ->
                stream.toList().assert().containsExactlyElementsOf(sent)
                stream.map { it.requestId }.assert().containsExactly("${event.id}-1", "${event.id}-2")
            }.verifyComplete()
        actions.assert().containsExactly(
            "create:skip",
            "create:first",
            "send:first",
            "create:second",
            "create:second",
            "send:second"
        )
    }

    @Test
    fun `void command retries use writable headers without changing identity`() {
        val attempts = mutableListOf<CommandMessage<*>>()
        val distributed = object : DistributedCommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.defer {
                attempts += message
                message.withReadOnly()
                if (attempts.size == 1) Mono.error(TimeoutException("broker send failed")) else Mono.empty()
            }
            override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
        }
        val bus = LocalFirstCommandBus(distributed)
        val gateway = mockk<me.ahoo.wow.command.CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers { bus.send(firstArg()) }
        }
        val saga = StatelessSagaFunction(
            StubMessageFunction(Mono.just(MockVoidCommand("value"))),
            gateway,
            commandMessageFactory()
        )
        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(fixtureEvent())).retry(1))
            .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(5))
        attempts.assert().hasSize(2)
        attempts.map { it.commandId to it.requestId }.distinct().assert().hasSize(1)
    }

    @Test
    fun `functions with matching metadata keep separate progress on a shared exchange`() {
        val sent = mutableListOf<String>()
        val gateway = mockk<me.ahoo.wow.command.CommandGateway> {
            every { send(any<CommandMessage<*>>()) } answers {
                sent += firstArg<CommandMessage<*>>().aggregateId.id
                Mono.empty()
            }
        }
        val first = StatelessSagaFunction(
            StubMessageFunction(Mono.just(MockChangeAggregate("first", "one"))),
            gateway,
            commandMessageFactory()
        )
        val second = StatelessSagaFunction(
            StubMessageFunction(Mono.just(MockChangeAggregate("second", "two"))),
            gateway,
            commandMessageFactory()
        )
        val exchange = SimpleDomainEventExchange(fixtureEvent())
        StepVerifier.create(first.invoke(exchange).then(second.invoke(exchange)).then(first.invoke(exchange)))
            .assertNext { it.single().aggregateId.id.assert().isEqualTo("first") }.verifyComplete()
        sent.assert().containsExactly("first", "second")
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
