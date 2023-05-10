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
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.modeling.AggregateChanged
import me.ahoo.wow.tck.modeling.ChangeAggregate
import me.ahoo.wow.tck.modeling.CreateAggregate
import me.ahoo.wow.tck.modeling.MockAggregate
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class RetryableAggregateProcessorTest {
    private val aggregateMetadata = aggregateMetadata<MockAggregate, MockAggregate>()
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
        val aggregateId = aggregateMetadata.asAggregateId()

        val retryableAggregateCommandHandler = RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = aggregateMetadata,
            aggregateFactory = ConstructorStateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
        )
        val create = CreateAggregate(aggregateId.id, GlobalIdGenerator.generateAsString())
            .asCommandMessage()
        retryableAggregateCommandHandler.process(SimpleServerCommandExchange(create, serviceProvider = serviceProvider))
            .test()
            .expectNextCount(1)
            .verifyComplete()

        retryableAggregateCommandHandler.process(SimpleServerCommandExchange(create, serviceProvider = serviceProvider))
            .test()
            .consumeErrorWith {
                assertThat(it, instanceOf(DuplicateAggregateIdException::class.java))
            }
            .verify()

        val change = ChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).asCommandMessage()
        retryableAggregateCommandHandler.process(SimpleServerCommandExchange(change, serviceProvider = serviceProvider))
            .test()
            .expectNextCount(1)
            .verifyComplete()

        val change2 = ChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).asCommandMessage()
        val eventStream = AggregateChanged(change2.body.state)
            .asDomainEventStream(change2, aggregateVersion = 2)
        eventStore.append(eventStream).block()

        val change3 = ChangeAggregate(aggregateId.id, GlobalIdGenerator.generateAsString()).asCommandMessage()
        retryableAggregateCommandHandler.process(
            SimpleServerCommandExchange(
                change3,
                serviceProvider = serviceProvider,
            ),
        )
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
