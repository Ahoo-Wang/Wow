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

import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class RetryableAggregateProcessorTest {
    private val aggregateMetadata = MOCK_AGGREGATE_METADATA
    private val serviceProvider: ServiceProvider = SimpleServiceProvider()
    private val eventStore = InMemoryEventStore()
    private val stateAggregateRepository =
        EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            eventStore,
        )

    @Test
    fun onCommand() {
        val aggregateId = aggregateMetadata.aggregateId()

        val retryableAggregateCommandHandler = RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = aggregateMetadata,
            aggregateFactory = ConstructorStateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
        )
        val create = MockCreateAggregate(aggregateId.id, GlobalIdGenerator.generateAsString())
            .toCommandMessage()
        retryableAggregateCommandHandler.process(
            SimpleServerCommandExchange(create).setServiceProvider(serviceProvider),
        )
            .test()
            .expectNextCount(1)
            .verifyComplete()

        retryableAggregateCommandHandler.process(
            SimpleServerCommandExchange(create).setServiceProvider(serviceProvider),
        )
            .test()
            .consumeErrorWith {
                assertThat(it, instanceOf(DuplicateAggregateIdException::class.java))
            }
            .verify()

        val change = MockChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).toCommandMessage()
        retryableAggregateCommandHandler.process(
            SimpleServerCommandExchange(change).setServiceProvider(serviceProvider),
        )
            .test()
            .expectNextCount(1)
            .verifyComplete()

        val change2 = MockChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).toCommandMessage()
        val eventStream = MockAggregateChanged(change2.body.data)
            .toDomainEventStream(change2, aggregateVersion = 2)
        eventStore.append(eventStream).block()

        val change3 = MockChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).toCommandMessage()
        retryableAggregateCommandHandler.process(
            SimpleServerCommandExchange(
                change3,
            ).setServiceProvider(serviceProvider),
        )
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
