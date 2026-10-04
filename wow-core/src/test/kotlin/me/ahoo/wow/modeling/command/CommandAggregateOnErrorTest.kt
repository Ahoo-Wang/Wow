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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.modeling.aware.VersionAware
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What `@OnError` receives and when it runs.
 *
 * [DirectProcessing] locks the semantics of one processing attempt (unchanged). [RetriedProcessing] covers the
 * retrying processor: `@OnError` runs once, after the final failure, and its result does not drive the retry (B4);
 * every attempt starts from a clean exchange (B5).
 */
class CommandAggregateOnErrorTest {
    private val metadata = aggregateMetadata<OnErrorProbeAggregate, OnErrorProbeAggregate>()
    private val aggregateId = metadata.aggregateId(AGGREGATE_ID)

    @BeforeEach
    fun resetProbe() {
        OnErrorProbe.reset()
    }

    private fun exchange(command: Any, aggregateVersion: Int? = null): ServerCommandExchange<*> =
        SimpleServerCommandExchange(
            command.toCommandMessage(
                aggregateId = AGGREGATE_ID,
                namedAggregate = metadata.namedAggregate,
                aggregateVersion = aggregateVersion,
            ),
        ).setServiceProvider(SimpleServiceProvider())

    @Nested
    inner class DirectProcessing {
        private fun commandAggregate(eventStore: EventStore = InMemoryEventStore()) =
            SimpleCommandAggregateFactory(eventStore).create(
                metadata,
                ConstructorStateAggregateFactory.create(metadata.state, aggregateId),
            )

        @Test
        fun `on error is not invoked when the command succeeds`() {
            StepVerifier.create(commandAggregate().process(exchange(ProbeCreate(AGGREGATE_ID))))
                .expectNextCount(1)
                .verifyComplete()

            OnErrorProbe.calls.assert().isEmpty()
        }

        @Test
        fun `on error receives the command handler error and no event stream`() {
            val exchange = exchange(ProbeCreate(AGGREGATE_ID, fail = true))

            StepVerifier.create(commandAggregate().process(exchange))
                .expectErrorMessage(HANDLER_FAILURE)
                .verify()

            val call = OnErrorProbe.calls.single()
            call.command.assert().isEqualTo(ProbeCreate(AGGREGATE_ID, fail = true))
            call.error.message.assert().isEqualTo(HANDLER_FAILURE)
            call.exchangeError.assert().isSameAs(call.error)
            call.eventStream.assert().isNull()
        }

        @Test
        fun `on error receives the append error and the event stream that was not stored`() {
            val failure = IllegalStateException("append failed")
            val exchange = exchange(ProbeCreate(AGGREGATE_ID))

            StepVerifier.create(commandAggregate(FailingEventStore(appendFailures = listOf(failure))).process(exchange))
                .expectErrorMatches { it === failure }
                .verify()

            val call = OnErrorProbe.calls.single()
            call.error.assert().isSameAs(failure)
            call.exchangeError.assert().isSameAs(failure)
            call.eventStream!!.single().body.assert().isEqualTo(ProbeCreated(AGGREGATE_ID))
        }

        @Test
        fun `on error runs for errors raised before the command handler`() {
            val exchange = exchange(ProbeChange(AGGREGATE_ID), aggregateVersion = 5)

            StepVerifier.create(commandAggregate().process(exchange))
                .expectError(CommandExpectVersionConflictException::class.java)
                .verify()

            val call = OnErrorProbe.calls.single()
            call.command.assert().isEqualTo(ProbeChange(AGGREGATE_ID))
            call.error.assert().isInstanceOf(CommandExpectVersionConflictException::class.java)
        }

        @Test
        fun `the error set by on error replaces the propagated error`() {
            val replacement = IllegalArgumentException("replaced")
            OnErrorProbe.reaction = { exchange, _ -> exchange.setError(replacement) }

            StepVerifier.create(commandAggregate().process(exchange(ProbeCreate(AGGREGATE_ID, fail = true))))
                .expectErrorMatches { it === replacement }
                .verify()
        }

        @Test
        fun `the original error propagates when on error clears the exchange error`() {
            OnErrorProbe.reaction = { exchange, _ -> exchange.clearError() }

            StepVerifier.create(commandAggregate().process(exchange(ProbeCreate(AGGREGATE_ID, fail = true))))
                .expectErrorMessage(HANDLER_FAILURE)
                .verify()
        }

        @Test
        fun `an error thrown by on error propagates instead`() {
            val thrown = IllegalStateException("on error failed")
            OnErrorProbe.reaction = { _, _ -> throw thrown }

            StepVerifier.create(commandAggregate().process(exchange(ProbeCreate(AGGREGATE_ID, fail = true))))
                .expectErrorMatches { it === thrown }
                .verify()
        }
    }

    @Nested
    inner class RetriedProcessing {
        private fun processor(
            eventStore: EventStore,
            aggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
        ) = RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = metadata,
            aggregateFactory = aggregateFactory,
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                ConstructorStateAggregateFactory,
                InMemorySnapshotStore(),
                eventStore,
            ),
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
        )

        @Test
        fun `on error runs once for a failure that is not retried`() {
            val failure = IllegalArgumentException("not recoverable")
            val eventStore = FailingEventStore(appendFailures = listOf(failure))

            StepVerifier.withVirtualTime { processor(eventStore).process(exchange(ProbeCreate(AGGREGATE_ID))) }
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorMatches { it === failure }
                .verify()

            OnErrorProbe.calls.single().error.assert().isSameAs(failure)
        }

        @Test
        fun `on error is not invoked when a retry succeeds`() {
            val eventStore = FailingEventStore(appendFailures = List(2) { TimeoutException("timeout") })

            StepVerifier.withVirtualTime { processor(eventStore).process(exchange(ProbeCreate(AGGREGATE_ID))) }
                .thenAwait(Duration.ofSeconds(10))
                .expectNextCount(1)
                .verifyComplete()

            OnErrorProbe.calls.assert().isEmpty()
        }

        @Test
        fun `on error runs once after the retries are exhausted`() {
            val failure = TimeoutException("timeout")
            val eventStore = FailingEventStore(appendFailures = List(16) { failure })
            val exchange = exchange(ProbeCreate(AGGREGATE_ID))

            StepVerifier.withVirtualTime { processor(eventStore).process(exchange) }
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorMatches { Exceptions.isRetryExhausted(it) && it.cause === failure }
                .verify()

            eventStore.appends.get().assert().isEqualTo(8)
            val call = OnErrorProbe.calls.single()
            call.error.assert().isSameAs(failure)
            call.exchangeError.assert().isSameAs(failure)
            call.eventStream.assert().isNotNull()
        }

        @Test
        fun `the error set by on error does not stop the retry`() {
            val replacement = IllegalArgumentException("not recoverable")
            OnErrorProbe.reaction = { exchange, _ -> exchange.setError(replacement) }
            val eventStore = FailingEventStore(appendFailures = List(16) { TimeoutException("timeout") })

            StepVerifier.withVirtualTime { processor(eventStore).process(exchange(ProbeCreate(AGGREGATE_ID))) }
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorMatches { it === replacement }
                .verify()

            eventStore.appends.get().assert().isEqualTo(8)
            OnErrorProbe.calls.assert().hasSize(1)
        }

        @Test
        fun `a retry attempt does not see the state of the failed attempt`() {
            val loadFailure = IllegalStateException("state unavailable")
            val creations = AtomicInteger()
            val aggregateFactory = object : StateAggregateFactory {
                override fun <S : Any> create(
                    metadata: StateAggregateMetadata<S>,
                    aggregateId: me.ahoo.wow.api.modeling.AggregateId,
                ): StateAggregate<S> {
                    if (creations.incrementAndGet() > 1) {
                        throw loadFailure
                    }
                    return ConstructorStateAggregateFactory.create(metadata, aggregateId)
                }
            }
            val eventStore = FailingEventStore(appendFailures = List(2) { TimeoutException("timeout") })
            val exchange = exchange(ProbeCreate(AGGREGATE_ID))

            StepVerifier.withVirtualTime { processor(eventStore, aggregateFactory).process(exchange) }
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorMatches { it === loadFailure }
                .verify()

            exchange.getEventStream().assert().isNull()
            exchange.getAggregateVersion().assert().isNull()
            exchange.getCommandInvokeResult<Any>().assert().isNull()
            exchange.getCommandAggregate<Any, Any>().assert().isNull()
        }

        @Test
        fun `a successful retry reports its own event stream and version`() {
            val eventStore = FailingEventStore(appendFailures = List(2) { TimeoutException("timeout") })
            val exchange = exchange(ProbeCreate(AGGREGATE_ID))

            StepVerifier.withVirtualTime { processor(eventStore).process(exchange) }
                .thenAwait(Duration.ofSeconds(10))
                .expectNextMatches { it === exchange.getEventStream() }
                .verifyComplete()

            exchange.getAggregateVersion().assert().isEqualTo(1)
            exchange.getError().assert().isNull()
        }
    }

    private class FailingEventStore(private val appendFailures: List<Throwable>) : EventStore {
        private val delegate = InMemoryEventStore()
        val appends = AtomicInteger()

        override fun append(eventStream: DomainEventStream): Mono<Void> {
            val failure = appendFailures.getOrNull(appends.getAndIncrement())
            return failure?.toMono() ?: delegate.append(eventStream)
        }

        override fun load(aggregateId: me.ahoo.wow.api.modeling.AggregateId, headVersion: Int, tailVersion: Int):
            Flux<DomainEventStream> = delegate.load(aggregateId, headVersion, tailVersion)

        override fun load(aggregateId: me.ahoo.wow.api.modeling.AggregateId, headEventTime: Long, tailEventTime: Long):
            Flux<DomainEventStream> = delegate.load(aggregateId, headEventTime, tailEventTime)

        override fun last(aggregateId: me.ahoo.wow.api.modeling.AggregateId): Mono<DomainEventStream> =
            delegate.last(aggregateId)
    }

    private companion object {
        const val AGGREGATE_ID = "probe-1"
        const val HANDLER_FAILURE = "handler failed"
    }
}

internal object OnErrorProbe {
    data class Call(
        val command: Any,
        val error: Throwable,
        val exchangeError: Throwable?,
        val eventStream: DomainEventStream?,
    )

    val calls: MutableList<Call> = mutableListOf()
    var reaction: (ServerCommandExchange<*>, Throwable) -> Unit = { _, _ -> }

    fun reset() {
        calls.clear()
        reaction = { _, _ -> }
    }

    fun record(command: Any, error: Throwable, eventStream: DomainEventStream?, exchange: ServerCommandExchange<*>) {
        calls += Call(command, error, exchange.getError(), eventStream)
        reaction(exchange, error)
    }
}

@CreateAggregate
data class ProbeCreate(@AggregateId val id: String, val fail: Boolean = false)

data class ProbeChange(@AggregateId val id: String)

data class ProbeCreated(val id: String)

data class ProbeChanged(val id: String)

@AggregateRoot
// Wow finds and invokes the private command, sourcing and error handlers by reflection.
@Suppress("UnusedPrivateMember")
class OnErrorProbeAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    private fun onCommand(command: ProbeCreate): ProbeCreated {
        check(!command.fail) { "handler failed" }
        return ProbeCreated(command.id)
    }

    private fun onCommand(command: ProbeChange): ProbeChanged = ProbeChanged(command.id)

    @Suppress("UnusedParameter")
    private fun onSourcing(event: ProbeCreated) = Unit

    @Suppress("UnusedParameter")
    private fun onSourcing(event: ProbeChanged) = Unit

    @OnError
    private fun onError(
        command: ProbeCreate,
        error: Throwable,
        eventStream: DomainEventStream?,
        exchange: ServerCommandExchange<*>,
    ) = OnErrorProbe.record(command, error, eventStream, exchange)

    @OnError
    private fun onError(
        command: ProbeChange,
        error: Throwable,
        eventStream: DomainEventStream?,
        exchange: ServerCommandExchange<*>,
    ) = OnErrorProbe.record(command, error, eventStream, exchange)
}
