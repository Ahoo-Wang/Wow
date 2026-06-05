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
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RetryableAggregateProcessorBehaviorTest {

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

    private fun processor(
        aggregateId: AggregateId,
        eventStore: EventStore,
    ): RetryableAggregateProcessor<me.ahoo.wow.tck.mock.MockCommandAggregate, me.ahoo.wow.tck.mock.MockStateAggregate> =
        RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            aggregateFactory = ConstructorStateAggregateFactory,
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                ConstructorStateAggregateFactory,
                InMemorySnapshotRepository(),
                eventStore,
            ),
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
        )

    private class RetryableEventStore : EventStore {
        private val delegate = InMemoryEventStore()
        val attempts = AtomicInteger()

        override fun append(eventStream: DomainEventStream): Mono<Void> {
            if (attempts.incrementAndGet() < 4) {
                return TimeoutException("timeout").toMono()
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
