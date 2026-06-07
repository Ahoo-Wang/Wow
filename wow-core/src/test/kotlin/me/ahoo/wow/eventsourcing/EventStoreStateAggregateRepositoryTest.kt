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

package me.ahoo.wow.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class EventStoreStateAggregateRepositoryTest {

    @Test
    fun `load replays events up to requested version from empty state`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val first = MockAggregateChanged("first").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId, requestId = "request-1"),
            aggregateVersion = 0,
            createTime = 1000,
        )
        val second = MockAggregateChanged("second").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId, requestId = "request-2"),
            aggregateVersion = 1,
            createTime = 2000,
        )
        eventStore.append(first).block()
        eventStore.append(second).block()
        val repository = EventStoreStateAggregateRepository(ConstructorStateAggregateFactory, eventStore)

        StepVerifier.create(repository.load(aggregateId, MOCK_AGGREGATE_METADATA.state, tailVersion = 1))
            .assertNext { stateAggregate ->
                stateAggregate.version.assert().isEqualTo(1)
                stateAggregate.state.data.assert().isEqualTo("first")
            }
            .verifyComplete()
        StepVerifier.create(repository.load(aggregateId, MOCK_AGGREGATE_METADATA.state, tailVersion = 2))
            .assertNext { stateAggregate ->
                stateAggregate.version.assert().isEqualTo(2)
                stateAggregate.state.data.assert().isEqualTo("second")
            }
            .verifyComplete()
    }

    @Test
    fun `load replays events up to requested event time from empty state`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val first = MockAggregateChanged("first").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId, requestId = "request-1"),
            aggregateVersion = 0,
            createTime = 1000,
        )
        val second = MockAggregateChanged("second").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId, requestId = "request-2"),
            aggregateVersion = 1,
            createTime = 2000,
        )
        eventStore.append(first).block()
        eventStore.append(second).block()
        val repository = EventStoreStateAggregateRepository(ConstructorStateAggregateFactory, eventStore)

        StepVerifier.create(repository.load(aggregateId, MOCK_AGGREGATE_METADATA.state, tailEventTime = 1000))
            .assertNext { stateAggregate ->
                stateAggregate.version.assert().isEqualTo(1)
                stateAggregate.state.data.assert().isEqualTo("first")
            }
            .verifyComplete()
    }
}
