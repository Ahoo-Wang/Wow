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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RetryableAggregateProcessorTest {

    @Test
    fun `processor defers state creation until requested and rebuilds on resubscription`() {
        val created = AtomicInteger()
        val aggregateFactory = object : StateAggregateFactory {
            override fun <S : Any> create(
                metadata: StateAggregateMetadata<S>,
                aggregateId: AggregateId,
            ): StateAggregate<S> {
                created.incrementAndGet()
                return ConstructorStateAggregateFactory.create(metadata, aggregateId)
            }
        }
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, InMemoryEventStore(), aggregateFactory)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())
        val result = processor.process(exchange)
        created.get().assert().isZero()

        StepVerifier.create(result, 0)
            .then { created.get().assert().isZero() }
            .thenCancel()
            .verify()

        StepVerifier.create(result)
            .expectNextCount(1)
            .verifyComplete()
        created.get().assert().isEqualTo(1)

        StepVerifier.create(result)
            .expectError(DuplicateAggregateIdException::class.java)
            .verify()
        created.get().assert().isEqualTo(2)
    }

    @Test
    fun `processor creates state for create commands and loads state for changes`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)

        StepVerifier.create(
            processor.process(
                SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
                    .setServiceProvider(SimpleServiceProvider())
            )
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            processor.process(
                SimpleServerCommandExchange(MockChangeAggregate("aggregate-1", "changed").toCommandMessage())
                    .setServiceProvider(SimpleServiceProvider())
            )
        )
            .expectNextCount(1)
            .verifyComplete()

        StepVerifier.create(
            processor.process(
                SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "duplicate").toCommandMessage())
                    .setServiceProvider(SimpleServiceProvider())
            )
        )
            .expectError(DuplicateAggregateIdException::class.java)
            .verify()
    }

    @Test
    fun `processor retries recoverable append failures and clears exchange error between attempts`() {
        val eventStore = RetryableEventStore()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())
        exchange.setError(IllegalStateException("stale"))

        StepVerifier.withVirtualTime { processor.process(exchange) }
            .thenAwait(Duration.ofSeconds(10))
            .expectNextCount(1)
            .verifyComplete()

        eventStore.attempts.get().assert().isEqualTo(4)
        exchange.getError().assert().isNull()
    }

    @Test
    fun `processor backs off before the first retry`() {
        val eventStore = RetryableEventStore(listOf(TimeoutException("timeout")))
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())

        StepVerifier.withVirtualTime { processor.process(exchange) }
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(499))
            .then { eventStore.attempts.get().assert().isEqualTo(1) }
            .thenAwait(Duration.ofSeconds(1))
            .expectNextCount(1)
            .verifyComplete()

        eventStore.attempts.get().assert().isEqualTo(2)
    }

    @Test
    fun `processor preserves retry exhaustion and resets the budget for each subscription`() {
        val failure = TimeoutException("timeout")
        val eventStore = RetryableEventStore(List(8) { failure })
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())
        val result = processor.process(exchange)

        repeat(2) { subscription ->
            StepVerifier.withVirtualTime { result }
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorMatches { Exceptions.isRetryExhausted(it) && it.cause === failure }
                .verify()

            eventStore.attempts.get().assert().isEqualTo((subscription + 1) * 4)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `processor stops at a non recoverable failure`(recoverableFailures: Int) {
        val failure = IllegalArgumentException("invalid command")
        val eventStore = RetryableEventStore(List(recoverableFailures) { TimeoutException("timeout") } + failure)
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())

        StepVerifier.withVirtualTime { processor.process(exchange) }
            .thenAwait(Duration.ofSeconds(10))
            .expectErrorMatches { it === failure }
            .verify()

        eventStore.attempts.get().assert().isEqualTo(recoverableFailures + 1)
    }

    @Test
    fun `processor cancellation during backoff prevents another attempt`() {
        val eventStore = RetryableEventStore()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val processor = processor(aggregateId, eventStore)
        val exchange = SimpleServerCommandExchange(MockCreateAggregate("aggregate-1", "created").toCommandMessage())
            .setServiceProvider(SimpleServiceProvider())

        StepVerifier.withVirtualTime {
            processor.process(exchange)
                .take(Duration.ofMillis(100))
                .then(Mono.delay(Duration.ofSeconds(10)))
        }
            .thenAwait(Duration.ofSeconds(11))
            .expectNext(0L)
            .verifyComplete()

        eventStore.attempts.get().assert().isEqualTo(1)
    }

    private fun processor(
        aggregateId: AggregateId,
        eventStore: EventStore,
        aggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory,
    ): RetryableAggregateProcessor<me.ahoo.wow.tck.mock.MockCommandAggregate, me.ahoo.wow.tck.mock.MockStateAggregate> =
        RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            aggregateFactory = aggregateFactory,
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                ConstructorStateAggregateFactory,
                InMemorySnapshotStore(),
                eventStore,
            ),
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
        )

    private class RetryableEventStore(
        private val failures: List<Throwable> = List(3) { TimeoutException("timeout") },
    ) : EventStore {
        private val delegate = InMemoryEventStore()
        val attempts = AtomicInteger()

        override fun append(eventStream: DomainEventStream): Mono<Void> {
            val failure = failures.getOrNull(attempts.getAndIncrement())
            if (failure != null) {
                return failure.toMono()
            }
            return delegate.append(eventStream)
        }

        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
            delegate.load(aggregateId, headVersion, tailVersion)

        override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> =
            delegate.load(aggregateId, headEventTime, tailEventTime)

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = delegate.last(aggregateId)
    }
}
