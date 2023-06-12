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
package me.ahoo.wow.tck.modeling.state

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEvent
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asNamedAggregate
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregate.Companion.asStateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

abstract class StateAggregateRepositorySpec {
    private val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

    protected abstract fun createStateAggregateRepository(
        aggregateFactory: StateAggregateFactory,
        eventStore: EventStore
    ): StateAggregateRepository

    @Suppress("UNCHECKED_CAST")
    @Test
    fun load() {
        val aggregateRepository = createStateAggregateRepository(TEST_AGGREGATE_FACTORY, TEST_EVENT_STORE)
        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())

        val command = GivenInitializationCommand(aggregateId)
        val stateChanged = MockAggregateChanged(GlobalIdGenerator.generateAsString())
        val eventStream = stateChanged.asDomainEventStream(command = command, aggregateVersion = 0)
        TEST_EVENT_STORE.append(eventStream).block()
        val stateAggregate = aggregateRepository.load(aggregateMetadata.state, aggregateId).block()!!
        assertThat(stateAggregate, notNullValue())
        assertThat(stateAggregate.aggregateId, equalTo(aggregateId))
        val domainEventMessage = eventStream.iterator().next() as DomainEvent<MockAggregateChanged>
        assertThat(stateAggregate.version, equalTo(domainEventMessage.version))
        assertThat(
            stateAggregate.state.data,
            equalTo(domainEventMessage.body.data),
        )
    }

    @Test
    fun loadWhenNotFound() {
        val aggregateRepository = createStateAggregateRepository(TEST_AGGREGATE_FACTORY, TEST_EVENT_STORE)
        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())
        aggregateRepository.load<MockStateAggregate>(aggregateId)
            .test()
            .consumeNextWith {
                assertThat(it.initialized, equalTo(false))
            }
            .verifyComplete()
    }

    @Test
    fun loadWhenAggregateTypeNull() {
        val aggregateRepository = createStateAggregateRepository(TEST_AGGREGATE_FACTORY, TEST_EVENT_STORE)
        val aggregateId = "test.test".asNamedAggregate().asAggregateId(GlobalIdGenerator.generateAsString())
        Assertions.assertThrows(IllegalStateException::class.java) {
            aggregateRepository.load<MockStateAggregate>(aggregateId)
        }
    }

    @Test
    fun loadWhenInitializedAndNoneEvent() {
        val stateAggregateFactory: StateAggregateFactory =
            object : StateAggregateFactory {
                override fun <S : Any> create(
                    metadata: StateAggregateMetadata<S>,
                    aggregateId: AggregateId
                ): Mono<StateAggregate<S>> {
                    val stateRoot = MockStateAggregate(aggregateId.id)
                    @Suppress("UNCHECKED_CAST")
                    return Mono.just(aggregateMetadata.asStateAggregate(stateRoot, 1) as StateAggregate<S>)
                }
            }
        val aggregateRepository = createStateAggregateRepository(stateAggregateFactory, TEST_EVENT_STORE)

        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())
        aggregateRepository.load(aggregateMetadata.state, aggregateId)
            .test()
            .assertNext { stateAggregate: StateAggregate<MockStateAggregate> ->
                assertThat(stateAggregate.initialized, equalTo(true))
                assertThat(stateAggregate.version, equalTo(1))
            }
            .verifyComplete()
    }

    companion object {
        protected val TEST_AGGREGATE_FACTORY: StateAggregateFactory = ConstructorStateAggregateFactory
        protected val TEST_EVENT_STORE: EventStore = InMemoryEventStore()
    }
}
