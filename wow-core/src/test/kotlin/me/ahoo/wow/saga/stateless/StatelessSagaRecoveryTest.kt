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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.toCommandMessage
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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.ioc.register
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class StatelessSagaRecoveryTest {
    @Test
    fun `retry resumes remaining commands using their original identities`() {
        val fixture = SagaRecoveryFixture()
        var failedCommandId: String? = null
        fixture.beforeSend = { command ->
            if (command.aggregateId.id == "second" && failedCommandId == null) {
                failedCommandId = command.commandId
                Mono.error(TimeoutException("send failed"))
            } else {
                Mono.empty()
            }
        }
        val function = fixture.function(
            listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two"))
        )
        val exchange = SimpleDomainEventExchange(fixture.event)
        StepVerifier.create(function.invoke(exchange).retryWhen(Retry.max(1)))
            .assertNext { stream ->
                stream.toList()[1].commandId.assert().isEqualTo(failedCommandId)
                fixture.attempts.map { it.aggregateId.id }.assert().containsExactly("first", "second", "second")
            }.verifyComplete()
    }

    @Test
    fun `different saga functions send distinct commands to the same aggregate`() {
        val fixture = SagaRecoveryFixture()
        val first = fixture.function(MockChangeAggregate("shared", "one"), name = "first")
        val second = fixture.function(MockChangeAggregate("shared", "two"), name = "second")
        StepVerifier.create(first.invoke(fixture.exchange()).then(second.invoke(fixture.exchange())))
            .expectNextCount(1).verifyComplete()
        fixture.attempts.map { it.requestId }.distinct().assert().hasSize(2)
    }

    @Test
    fun `request identities distinguish contexts processors and delimiter characters`() {
        val fixture = SagaRecoveryFixture()
        val functions = listOf(
            fixture.function(MockChangeAggregate("shared", "one"), contextName = "a:b", processorName = "c"),
            fixture.function(MockChangeAggregate("shared", "two"), contextName = "a", processorName = "b:c"),
            fixture.function(MockChangeAggregate("shared", "three"), contextName = "a", processorName = "c"),
        )
        StepVerifier.create(Flux.fromIterable(functions).concatMap { it.invoke(fixture.exchange()) })
            .expectNextCount(3).verifyComplete()
        fixture.attempts.map { it.requestId }.distinct().assert().hasSize(3)
    }

    @Test
    fun `replayed event recovers the persisted command instead of emitting another one`() {
        val fixture = SagaRecoveryFixture()
        val body = MockChangeAggregate("replayed", "one")
        val first = fixture.function(body).invoke(fixture.exchange()).block(Duration.ofSeconds(5))!!.single()
        StepVerifier.create(fixture.function(body).invoke(fixture.exchange()))
            .assertNext { it.single().commandId.assert().isEqualTo(first.commandId) }
            .verifyComplete()
        fixture.attempts.assert().hasSize(1)
    }

    @Test
    fun `ordinary cold-cache redelivery restores identity before an asynchronous broker accepts another send`() {
        val first = SagaRecoveryFixture()
        val body = MockChangeAggregate("cold-replay", "one")
        val original = first.function(body).invoke(first.exchange()).block(Duration.ofSeconds(5))!!.single()
        val restarted = SagaRecoveryFixture(event = first.event, store = first.store).apply { brokerOnly = true }
        StepVerifier.create(restarted.function(body).invoke(restarted.exchange()))
            .assertNext { it.single().commandId.assert().isEqualTo(original.commandId) }
            .verifyComplete()
        restarted.attempts.assert().isEmpty()
        restarted.recoveryReads.get().assert().isEqualTo(1)
    }

    @Test
    fun `current signature record takes priority over an earlier ambiguous legacy record`() {
        val fixture = SagaRecoveryFixture()
        val body = MockChangeAggregate("legacy-target", "one")
        val current = fixture.function(body).invoke(fixture.exchange()).block(Duration.ofSeconds(5))!!.single()
        val history = InMemoryEventStore()
        val older = body.toCommandMessage(
            id = "legacy-command",
            requestId = "${fixture.event.id}-0",
            tenantId = fixture.event.aggregateId.tenantId,
            spaceId = fixture.event.spaceId,
        )
        history.append(MockAggregateCreated("older").toDomainEventStream(older))
            .then(history.append(MockAggregateCreated("current").toDomainEventStream(current, aggregateVersion = 1)))
            .block(Duration.ofSeconds(5))
        val restarted = SagaRecoveryFixture(fixture.event, history).apply { brokerOnly = true }
        StepVerifier.create(restarted.function(body).invoke(restarted.exchange()))
            .assertNext { it.single().commandId.assert().isEqualTo(current.commandId) }.verifyComplete()
        restarted.attempts.assert().isEmpty()
        restarted.recoveryReads.get().assert().isEqualTo(1)
    }

    @Test
    fun `legacy requests report ambiguity even on ordinary cold-cache redelivery`() {
        val fixture = SagaRecoveryFixture()
        val original = fixture.storeLegacyCommand()
        fixture.brokerOnly = true
        val function = fixture.function(MockChangeAggregate("legacy-target", "from-another-function"), name = "another")
        StepVerifier.create(function.invoke(fixture.exchange()))
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(DuplicateRequestIdException::class.java)
                (error as DuplicateRequestIdException).requestId.assert().isEqualTo(original.requestId)
                error.message.assert().contains("legacy")
            }.verify()
        fixture.attempts.assert().isEmpty()
    }

    @Test
    fun `fresh commands perform one candidate lookup without history scans`() {
        val fixture = SagaRecoveryFixture()
        StepVerifier.create(fixture.function(MockChangeAggregate("fresh", "one")).invoke(fixture.exchange()))
            .expectNextCount(1).verifyComplete()
        fixture.recoveryReads.get().assert().isEqualTo(1)
        fixture.historyReads.get().assert().isEqualTo(0)
    }

    @Test
    fun `explicit compensation uses one record read without existence probes`() {
        val fixture = SagaRecoveryFixture()
        val body = MockChangeAggregate("replayed", "one")
        val function = fixture.function(body)
        val original = function.invoke(fixture.exchange()).block(Duration.ofSeconds(5))!!.single()
        fixture.recoveryReads.set(0)
        StepVerifier.create(function.invoke(fixture.compensationExchange(function)))
            .assertNext { it.single().commandId.assert().isEqualTo(original.commandId) }
            .verifyComplete()
        fixture.recoveryReads.get().assert().isEqualTo(1)
        fixture.attempts.assert().hasSize(1)
    }

    @Test
    fun `registered overloads have distinct stable request identities`() {
        val fixture = SagaRecoveryFixture()
        val registrar = StatelessSagaFunctionRegistrar(fixture.gateway, commandMessageFactory())
        registrar.registerProcessor(OverloadedSaga())
        val functions = registrar.supportedFunctions(fixture.event).toList()
        functions.assert().hasSize(2)
        StepVerifier.create(Flux.fromIterable(functions).concatMap { it.invoke(fixture.exchange()) })
            .expectNextCount(2).verifyComplete()
        fixture.attempts.map { it.requestId }.distinct().assert().hasSize(2)
        val ids = fixture.attempts.map { it.commandId }.toSet()
        val reloadedFunctions = StatelessSagaFunctionRegistrar(fixture.gateway, commandMessageFactory())
            .resolveProcessor(OverloadedSaga())
        StepVerifier.create(
            Flux.fromIterable(reloadedFunctions).concatMap {
                it.invoke(fixture.compensationExchange(it)).cast(CommandStream::class.java)
            }.flatMapIterable { it }.map { it.commandId }.collectList()
        )
            .assertNext { it.toSet().assert().isEqualTo(ids) }.verifyComplete()
        fixture.attempts.assert().hasSize(2)
    }

    @Test
    fun `retry does not leave a failed sent signal in the waiting chain`() {
        val fixture = SagaRecoveryFixture()
        val plan = fixture.chainPlan(CommandStage.PROCESSED)
        val handle = fixture.coordinator.createLast(plan)
        fixture.signal(CommandStage.PROCESSED, fixture.event.commandId)
        var failSecond = true
        fixture.beforeSend = { command ->
            if (command.aggregateId.id == "second" && failSecond) {
                failSecond = false
                Mono.error(TimeoutException("send failed"))
            } else {
                Mono.empty()
            }
        }
        fixture.afterSend = { fixture.signal(CommandStage.PROCESSED, it.commandId) }
        val function = fixture.function(
            listOf(MockChangeAggregate("first", "one"), MockChangeAggregate("second", "two"))
        )
        val exchange = fixture.exchange().setFunction(function)
        StepVerifier.create(
            function.invoke(exchange).retryWhen(Retry.max(1)).then()
                .thenNotifyAndForget(fixture.notifier, CommandStage.SAGA_HANDLED, exchange)
                .then(handle.await())
        ).assertNext { it.succeeded.assert().isTrue() }
            .expectComplete().verify(Duration.ofSeconds(5))
    }

    @Test
    fun `terminal saga send failure still completes the waiting chain with an error`() {
        val fixture = SagaRecoveryFixture()
        val handle = fixture.coordinator.createLast(fixture.chainPlan(CommandStage.PROCESSED))
        fixture.signal(CommandStage.PROCESSED, fixture.event.commandId)
        fixture.beforeSend = { Mono.error(TimeoutException("unavailable")) }
        val function = fixture.function(MockChangeAggregate("failed", "one"))
        val exchange = fixture.exchange().setFunction(function)
        StepVerifier.create(
            function.invoke(exchange).retryWhen(Retry.max(1)).then()
                .thenNotifyAndForget(fixture.notifier, CommandStage.SAGA_HANDLED, exchange)
        ).expectError(TimeoutException::class.java).verify()
        StepVerifier.create(handle.await())
            .assertNext { it.succeeded.assert().isFalse() }.verifyComplete()
    }

    @Test
    fun `persisted recovery matches the original processed notification`() {
        val fixture = SagaRecoveryFixture()
        fixture.storeLegacyCommand()
        val handle = fixture.coordinator.createLast(fixture.chainPlan(CommandStage.PROCESSED))
        fixture.signal(CommandStage.PROCESSED, fixture.event.commandId)
        fixture.signal(CommandStage.PROCESSED, "legacy-command")
        val function = fixture.function(
            MockChangeAggregate("legacy-target", "one").commandBuilder().requestId("${fixture.event.id}-0")
        )
        val exchange = fixture.exchange().setFunction(function)
        StepVerifier.create(
            function.invoke(exchange).then()
                .thenNotifyAndForget(fixture.notifier, CommandStage.SAGA_HANDLED, exchange)
                .then(handle.await())
        ).assertNext {
            it.succeeded.assert().isTrue()
            it.commandId.assert().isEqualTo("legacy-command")
        }.expectComplete().verify(Duration.ofSeconds(5))
    }

    @Test
    fun `persisted recovery waits for actual projection completion`() {
        val fixture = SagaRecoveryFixture()
        fixture.storeLegacyCommand()
        val handle = fixture.coordinator.createLast(fixture.chainPlan(CommandStage.PROJECTED))
        fixture.signal(CommandStage.PROCESSED, fixture.event.commandId)
        fixture.signal(CommandStage.PROCESSED, "legacy-command")
        val function = fixture.function(
            MockChangeAggregate("legacy-target", "one").commandBuilder().requestId("${fixture.event.id}-0")
        )
        val exchange = fixture.exchange().setFunction(function)
        StepVerifier.create(
            function.invoke(exchange).then()
                .thenNotifyAndForget(fixture.notifier, CommandStage.SAGA_HANDLED, exchange)
                .then(handle.await())
        ).then {
            (fixture.event.commandId in fixture.coordinator).assert().isTrue()
            fixture.signal(CommandStage.PROJECTED, "legacy-command")
        }.assertNext { it.succeeded.assert().isTrue() }
            .expectComplete().verify(Duration.ofSeconds(5))
    }

    @Test
    fun `stored events do not fabricate completion of command processing`() {
        val fixture = SagaRecoveryFixture()
        fixture.storeLegacyCommand()
        val handle = fixture.coordinator.createLast(fixture.chainPlan(CommandStage.PROCESSED))
        fixture.signal(CommandStage.PROCESSED, fixture.event.commandId)
        val function = fixture.function(
            MockChangeAggregate("legacy-target", "one").commandBuilder().requestId("${fixture.event.id}-0")
        )
        val exchange = fixture.exchange().setFunction(function)
        StepVerifier.create(
            function.invoke(exchange).then()
                .thenNotifyAndForget(fixture.notifier, CommandStage.SAGA_HANDLED, exchange)
                .then(handle.await())
        ).then {
            (fixture.event.commandId in fixture.coordinator).assert().isTrue()
            fixture.signal(CommandStage.PROCESSED, "legacy-command")
        }.assertNext { it.succeeded.assert().isTrue() }
            .expectComplete().verify(Duration.ofSeconds(5))
    }

    @Test
    fun `unverified duplicate failures are propagated`() {
        val fixture = SagaRecoveryFixture()
        fixture.beforeSend = { Mono.error(DuplicateRequestIdException(it.aggregateId, it.requestId)) }
        StepVerifier.create(fixture.function(MockChangeAggregate("unknown", "one")).invoke(fixture.exchange()))
            .expectError(DuplicateRequestIdException::class.java).verify()
    }
}

private class SagaRecoveryFixture(
    val event: DomainEvent<*> = fixtureEvent(),
    val store: InMemoryEventStore = InMemoryEventStore(),
) {
    val recoveryReads = AtomicInteger()
    val historyReads = AtomicInteger()
    private val recoveryStore = object : EventStore by store {
        override fun loadByRequestIds(aggregateId: AggregateId, requestIds: Set<String>): Flux<DomainEventStream> {
            recoveryReads.incrementAndGet()
            return store.loadByRequestIds(aggregateId, requestIds)
        }
        override fun existsRequestId(aggregateId: AggregateId, requestId: String): Mono<Boolean> {
            recoveryReads.incrementAndGet()
            return store.existsRequestId(aggregateId, requestId)
        }
        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
            historyReads.incrementAndGet()
            return store.load(aggregateId, headVersion, tailVersion)
        }
    }
    val coordinator = DefaultWaitCoordinator()
    val attempts = mutableListOf<CommandMessage<*>>()
    var brokerOnly = false
    var beforeSend: (CommandMessage<*>) -> Mono<Void> = { Mono.empty() }
    var afterSend: (CommandMessage<*>) -> Unit = {}
    val notifier = object : CommandWaitNotifier {
        override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> =
            Mono.fromRunnable { coordinator.signal(waitSignal) }
    }
    private val provider = SimpleServiceProvider().apply {
        register<EventStore>(recoveryStore)
        register<CommandWaitNotifier>(notifier)
    }
    private val bus = object : CommandBus {
        override fun send(message: CommandMessage<*>): Mono<Void> = Mono.defer {
            attempts += message
            message.withReadOnly()
            if (brokerOnly) {
                return@defer beforeSend(message)
            }
            beforeSend(message).then(
                store.last(message.aggregateId).map { it.version }.defaultIfEmpty(0)
                    .flatMap { version ->
                        store.append(
                            MockAggregateCreated("stored").toDomainEventStream(message, version)
                        )
                    }
            ).doOnSuccess { afterSend(message) }
        }
        override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
    }
    private val seen = mutableSetOf<String>()
    val gateway = DefaultCommandGateway(
        SimpleCommandWaitEndpoint("local"),
        bus,
        NoOpValidator,
        DefaultRequestIdChecker(AggregateIdempotencyCheckerProvider { IdempotencyChecker { seen.add(it) } }, store),
        coordinator,
        notifier,
    )

    fun exchange() = SimpleDomainEventExchange(event).setServiceProvider(provider)

    fun compensationExchange(function: MessageFunction<*, *, *>) = exchange().also {
        it.message.header.withCompensation(
            CompensationTarget(
                function = FunctionInfoData(
                    FunctionKind.EVENT,
                    function.contextName,
                    function.processorName,
                    function.name,
                )
            )
        )
    }

    fun function(
        result: Any,
        name: String = "onEvent",
        contextName: String = "fixture",
        processorName: String = "test-saga",
    ): StatelessSagaFunction = StatelessSagaFunction(
        object : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> by StubMessageFunction(Mono.just(result)) {
            override val name = name
            override val contextName = contextName
            override val processorName = processorName
        },
        gateway,
        commandMessageFactory(),
    )

    fun storeLegacyCommand(): CommandMessage<*> {
        val command = MockChangeAggregate("legacy-target", "one").toCommandMessage(
            id = "legacy-command",
            requestId = "${event.id}-0",
            tenantId = event.aggregateId.tenantId,
            spaceId = event.spaceId,
        )
        store.append(MockAggregateCreated("stored").toDomainEventStream(command)).block(Duration.ofSeconds(5))
        return command
    }

    fun chainPlan(stage: CommandStage) = CommandWait.chain(
        event.commandId,
        NamedFunctionInfoData("fixture", "test-saga", "onEvent"),
        stage.toWaitingChainTail(NamedFunctionInfoData("projection", "projection", "onEvent")),
    ).also { it.propagate(SimpleCommandWaitEndpoint("local"), event.header) }

    fun signal(stage: CommandStage, commandId: String) {
        coordinator.signal(
            testSignal(
                stage = stage,
                waitCommandId = event.commandId,
                commandId = commandId,
                function = testFunction(contextName = "projection", processorName = "projection", name = "onEvent"),
                isLastProjection = true,
            )
        )
    }
}

@StatelessSaga
private class OverloadedSaga {
    @Suppress("UNUSED_PARAMETER")
    fun onEvent(event: MockAggregateCreated) = MockChangeAggregate("shared", "body-overload")

    @Suppress("UNUSED_PARAMETER")
    fun onEvent(event: DomainEvent<MockAggregateCreated>) = MockChangeAggregate("shared", "message-overload")
}
