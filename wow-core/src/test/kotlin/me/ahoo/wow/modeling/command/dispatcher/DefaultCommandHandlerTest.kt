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

package me.ahoo.wow.modeling.command.dispatcher

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.testCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.setCommandAggregate
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The fixed command pipeline that replaced the command filter chain (V5): what runs, in which order, and where each
 * failure stops.
 */
class DefaultCommandHandlerTest {
    private lateinit var calls: MutableList<String>
    private lateinit var notifier: RecordingCommandWaitNotifier
    private lateinit var domainEventBus: RecordingDomainEventBus
    private lateinit var stateEventBus: RecordingStateEventBus
    private lateinit var exchange: RecordingExchange<*>

    @BeforeEach
    fun setUp() {
        calls = mutableListOf()
        notifier = RecordingCommandWaitNotifier()
        domainEventBus = RecordingDomainEventBus(calls)
        stateEventBus = RecordingStateEventBus(calls)
        exchange = RecordingExchange(testCommandExchange(stage = CommandStage.PROCESSED).message, calls)
    }

    private fun handler(
        processing: (RecordingExchange<*>) -> Mono<DomainEventStream>,
        domainEventBus: DomainEventBus? = this.domainEventBus,
        stateEventBus: StateEventBus? = this.stateEventBus,
        instrumentations: List<CommandInstrumentation> = emptyList(),
        requestIdChecker: RequestIdChecker? = null,
        errorHandler: ErrorHandler<ServerCommandExchange<*>> = ErrorHandler { _, error ->
            calls += "error-handler:${error.message}"
            Mono.empty()
        },
    ) = DefaultCommandHandler(
        serviceProvider = SimpleServiceProvider(),
        aggregateProcessorFactory = StubProcessorFactory(calls, processing),
        domainEventBus = domainEventBus,
        stateEventBus = stateEventBus,
        commandWaitNotifier = notifier,
        instrumentations = instrumentations,
        requestIdChecker = requestIdChecker,
        errorHandler = errorHandler,
    )

    private fun eventStream(aggregateVersion: Int = 0): DomainEventStream =
        MockAggregateCreated("created").toDomainEventStream(
            upstream = exchange.message,
            aggregateVersion = aggregateVersion,
        )

    /** Processing that commits [eventStream] and leaves a state at [stateVersion] on the exchange. */
    private fun committing(
        eventStream: DomainEventStream,
        stateVersion: Int
    ): (RecordingExchange<*>) -> Mono<DomainEventStream> =
        {
            it.setCommandAggregate(commandAggregate(it.message.aggregateId, stateVersion))
            it.setEventStream(eventStream)
            Mono.just(eventStream)
        }

    private fun commandAggregate(aggregateId: AggregateId, version: Int): CommandAggregate<Any, MockStateAggregate> {
        val stateAggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = MockStateAggregate(aggregateId.id),
            version = version,
        )
        return mockk { every { state } returns stateAggregate }
    }

    private fun handle(handler: DefaultCommandHandler) {
        StepVerifier.create(handler.handle(exchange, MOCK_AGGREGATE_METADATA)).verifyComplete()
    }

    @Test
    fun `processes, acknowledges, publishes the domain events and then the state event`() {
        val eventStream = eventStream()

        handle(handler(committing(eventStream, stateVersion = 1)))

        calls.assert().isEqualTo(listOf("process", "ack", "domain-event", "state-event"))
        domainEventBus.sent.single().assert().isSameAs(eventStream)
        stateEventBus.sent.single().version.assert().isEqualTo(eventStream.version)
        notifier.notifications.single().signal.stage.assert().isEqualTo(CommandStage.PROCESSED)
        notifier.notifications.single().signal.succeeded.assert().isTrue()
    }

    @Test
    fun `a command that produced no events is acknowledged and publishes nothing`() {
        handle(handler({ Mono.empty() }))

        calls.assert().isEqualTo(listOf("process", "ack"))
        notifier.notifications.single().signal.succeeded.assert().isTrue()
    }

    @Test
    fun `a processing failure is acknowledged, publishes nothing and reaches the error handler`() {
        val failure = IllegalStateException("processing failed")

        handle(handler({ Mono.error(failure) }))

        calls.assert().isEqualTo(listOf("process", "ack", "error-handler:processing failed"))
        exchange.getError().assert().isSameAs(failure)
        notifier.notifications.single().signal.succeeded.assert().isFalse()
    }

    @Test
    fun `a request id the processing node already committed fails the command without running the aggregate`() {
        val checked = mutableListOf<String>()
        val requestIdChecker = RequestIdChecker { aggregateId, requestId ->
            checked += "${aggregateId.id}/$requestId"
            Mono.just(false)
        }

        handle(handler({ Mono.just(eventStream()) }, requestIdChecker = requestIdChecker))

        checked.assert().containsExactly("${exchange.message.aggregateId.id}/${exchange.message.requestId}")
        calls.assert().hasSize(2)
        calls[0].assert().isEqualTo("ack")
        calls[1].assert().startsWith("error-handler:")
        exchange.getError().assert().isInstanceOf(DuplicateRequestIdException::class.java)
        val signal = notifier.notifications.single().signal
        signal.stage.assert().isEqualTo(CommandStage.PROCESSED)
        signal.errorCode.assert().isEqualTo(ErrorCodes.DUPLICATE_REQUEST_ID)
        domainEventBus.sent.assert().isEmpty()
    }

    @Test
    fun `a new request id passes the processing node check and the aggregate runs`() {
        handle(
            handler(
                committing(eventStream(), stateVersion = 1),
                requestIdChecker = RequestIdChecker { _, _ -> Mono.just(true) },
            )
        )

        calls.assert().isEqualTo(listOf("process", "ack", "domain-event", "state-event"))
    }

    @Test
    fun `the processing node check confirms a resent command against the event store`() {
        // The gateway's own Bloom filter is not shared: the processing node keeps its own, and asks the event store
        // only when that filter has seen the request ID.
        val eventStore = InMemoryEventStore()
        val requestIdChecker = DefaultRequestIdChecker(
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
                    BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 1000)
                }
            },
            requestIdExistenceChecker = eventStore,
        )
        val committed = eventStream()
        handle(
            handler(
                {
                    eventStore.append(committed).thenReturn(committed)
                },
                requestIdChecker = requestIdChecker,
            )
        )
        exchange.getError().assert().isNull()

        exchange = RecordingExchange(exchange.message, calls)
        handle(handler({ Mono.error(IllegalStateException("must not run")) }, requestIdChecker = requestIdChecker))

        exchange.getError().assert().isInstanceOf(DuplicateRequestIdException::class.java)
    }

    @Test
    fun `a domain event bus failure skips the state event and reaches the error handler`() {
        domainEventBus.failure = IllegalStateException("domain bus failed")

        handle(handler(committing(eventStream(), stateVersion = 1)))

        calls.assert().isEqualTo(listOf("process", "ack", "domain-event", "error-handler:domain bus failed"))
        notifier.notifications.single().signal.succeeded.assert().isFalse()
    }

    @Test
    fun `a state event bus failure is logged and the command still succeeds`() {
        stateEventBus.failure = IllegalStateException("state bus failed")

        handle(handler(committing(eventStream(), stateVersion = 1)))

        calls.assert().isEqualTo(listOf("process", "ack", "domain-event", "state-event"))
        exchange.getError().assert().isNull()
        notifier.notifications.single().signal.succeeded.assert().isTrue()
    }

    /** B9: a state that failed to apply the stream stays at its previous version and is not published. */
    @Test
    fun `a state that did not apply the event stream is not published`() {
        handle(handler(committing(eventStream(aggregateVersion = 1), stateVersion = 1)))

        calls.assert().isEqualTo(listOf("process", "ack", "domain-event"))
        stateEventBus.sent.assert().isEmpty()
    }

    @Test
    fun `an uninitialized state is not published`() {
        handle(handler(committing(eventStream(), stateVersion = 0)))

        stateEventBus.sent.assert().isEmpty()
    }

    @Test
    fun `a missing bus skips its step`() {
        handle(handler(committing(eventStream(), stateVersion = 1), domainEventBus = null, stateEventBus = null))

        calls.assert().isEqualTo(listOf("process", "ack"))
        notifier.notifications.single().signal.succeeded.assert().isTrue()
    }

    @Test
    fun `instrumentations wrap the whole handling in order, the first outermost`() {
        val instrumentations = listOf(
            RecordingInstrumentation("outer", calls),
            RecordingInstrumentation("inner", calls),
        )

        handle(handler(committing(eventStream(), stateVersion = 1), instrumentations = instrumentations))

        calls.assert().isEqualTo(
            listOf(
                "outer:start",
                "inner:start",
                "process",
                "ack",
                "domain-event",
                "state-event",
                "inner:end",
                "outer:end",
            ),
        )
    }

    @Test
    fun `instrumentations follow their @Order`() {
        val instrumentations = listOf(RecordingInstrumentation("unordered", calls), FirstInstrumentation(calls))

        handle(handler({ Mono.empty() }, instrumentations = instrumentations))

        calls.first().assert().isEqualTo("first:start")
    }

    @Test
    fun `an instrumentation observes a failure before the error handler`() {
        handle(
            handler(
                { Mono.error(IllegalStateException("processing failed")) },
                instrumentations = listOf(RecordingInstrumentation("observer", calls)),
            ),
        )

        calls.assert().isEqualTo(
            listOf("observer:start", "process", "ack", "observer:error", "error-handler:processing failed"),
        )
    }

    private open class RecordingInstrumentation(
        private val name: String,
        private val calls: MutableList<String>
    ) : CommandInstrumentation {
        override fun around(exchange: ServerCommandExchange<*>, handling: Mono<Void>): Mono<Void> =
            Mono.defer {
                calls += "$name:start"
                handling
            }.doOnSuccess { calls += "$name:end" }
                .doOnError { calls += "$name:error" }
    }

    @Order(-1)
    private class FirstInstrumentation(calls: MutableList<String>) : RecordingInstrumentation("first", calls)

    private class StubProcessorFactory(
        private val calls: MutableList<String>,
        private val processing: (RecordingExchange<*>) -> Mono<DomainEventStream>
    ) : AggregateProcessorFactory {
        override fun <C : Any, S : Any> create(
            aggregateId: AggregateId,
            aggregateMetadata: AggregateMetadata<C, S>
        ): AggregateProcessor<C> =
            object : AggregateProcessor<C>, me.ahoo.wow.api.modeling.NamedTypedAggregate<C> by aggregateMetadata.command {
                override val aggregateId: AggregateId = aggregateId
                override val processorName: String = "StubProcessor"

                override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> =
                    Mono.defer {
                        calls += "process"
                        processing(exchange as RecordingExchange<*>)
                    }
            }
    }

    private class RecordingExchange<C : Any>(
        override val message: CommandMessage<C>,
        private val calls: MutableList<String>,
    ) : ServerCommandExchange<C> {
        override val attributes: MutableMap<String, Any> = ConcurrentHashMap()

        override fun acknowledge(): Mono<Void> = Mono.fromRunnable { calls += "ack" }
    }

    private class RecordingDomainEventBus(private val calls: MutableList<String>) : DomainEventBus {
        val sent = mutableListOf<DomainEventStream>()
        var failure: Throwable? = null

        override fun send(message: DomainEventStream): Mono<Void> =
            Mono.defer {
                calls += "domain-event"
                sent += message
                failure?.let { Mono.error(it) } ?: Mono.empty()
            }

        override fun receiver(subscription: MessageSubscription): MessageReceiver<EventStreamExchange> =
            MessageReceiver(Flux.empty())
    }

    private class RecordingStateEventBus(private val calls: MutableList<String>) : StateEventBus {
        val sent = mutableListOf<StateEvent<*>>()
        var failure: Throwable? = null

        override fun send(message: StateEvent<*>): Mono<Void> =
            Mono.defer {
                calls += "state-event"
                sent += message
                failure?.let { Mono.error(it) } ?: Mono.empty()
            }

        override fun receiver(subscription: MessageSubscription): MessageReceiver<StateEventExchange<*>> =
            MessageReceiver(Flux.empty())
    }
}
