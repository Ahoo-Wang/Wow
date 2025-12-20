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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

internal class SnapshotDispatcherTest {
    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    @Test
    fun start() {
        val stateEventBus = InMemoryStateEventBus()
        val inMemorySnapshotRepository = InMemorySnapshotRepository()
        val waitForAppend = Sinks.empty<Void>()
        val snapshotRepository = object : SnapshotRepository {
            override val name: String
                get() = "TEST"
            override fun <S : Any> load(
                aggregateId: AggregateId
            ): Mono<Snapshot<S>> {
                return inMemorySnapshotRepository.load(aggregateId)
            }

            override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
                return inMemorySnapshotRepository.save(snapshot).then(
                    Mono.defer {
                        waitForAppend.tryEmitEmpty()
                        Mono.empty()
                    },
                )
            }

            override fun scanAggregateId(
                namedAggregate: NamedAggregate,
                afterId: String,
                limit: Int
            ): Flux<AggregateId> {
                return inMemorySnapshotRepository.scanAggregateId(namedAggregate, afterId, limit)
            }
        }
        val snapshotStrategy = SimpleSnapshotStrategy(
            snapshotRepository = snapshotRepository,
        )
        val snapshotFunctionFilter = SnapshotFunctionFilter(
            snapshotStrategy = snapshotStrategy,
        )
        val chain = FilterChainBuilder<StateEventExchange<*>>()
            .addFilter(snapshotFunctionFilter)
            .filterCondition(SnapshotDispatcher::class)
            .build()
        val handler = DefaultSnapshotHandler(chain).metrizable()
        val snapshotDispatcher =
            SnapshotDispatcher(
                name = "test",
                namedAggregates = setOf(aggregateMetadata.materialize()),
                snapshotHandler = handler,
                stateEventBus = stateEventBus,
            )
        snapshotDispatcher.start()
        val aggregateId = aggregateMetadata.aggregateId()
        val createdEventStream = MockAggregateCreated(GlobalIdGenerator.generateAsString())
            .toDomainEventStream(GivenInitializationCommand(aggregateId), 0)
        val state = MockStateAggregate(createdEventStream.aggregateId.id)
        val stateEvent = createdEventStream.toStateEvent(state)
        stateEventBus.send(stateEvent).block()
        waitForAppend.asMono().block()
        snapshotRepository.load<MockStateAggregate>(aggregateId)
            .test()
            .expectNextCount(1)
            .verifyComplete()

        snapshotDispatcher.close()
    }
}
