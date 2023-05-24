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
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.modeling.AggregateCreated
import me.ahoo.wow.tck.modeling.MockAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

internal class SnapshotDispatcherTest {
    protected val aggregateMetadata =
        aggregateMetadata<MockAggregate, MockAggregate>()

    @Test
    fun start() {
        val eventStore = InMemoryEventStore()
        val domainEventBus = InMemoryDomainEventBus()
        val inMemorySnapshotRepository = InMemorySnapshotRepository()
        val waitForAppend = Sinks.empty<Void>()
        val snapshotRepository = object : SnapshotRepository {
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

            override fun scrollAggregateId(
                namedAggregate: NamedAggregate,
                cursorId: String,
                limit: Int
            ): Flux<AggregateId> {
                return inMemorySnapshotRepository.scrollAggregateId(namedAggregate, cursorId, limit)
            }
        }
        val snapshotStrategy = SimpleSnapshotStrategy(
            matcher = MATCH_ALL,
            snapshotRepository = snapshotRepository,
            eventStore = eventStore,
        )
        val snapshotFunctionFilter = SnapshotFunctionFilter(
            snapshotStrategy = snapshotStrategy,
        )
        val chain = FilterChainBuilder<EventStreamExchange>()
            .addFilter(snapshotFunctionFilter)
            .filterCondition(SnapshotDispatcher::class)
            .build()
        val handler = DefaultSnapshotHandler(chain).metrizable()
        val snapshotDispatcher =
            SnapshotDispatcher(
                name = "test",
                namedAggregates = setOf(aggregateMetadata.materialize()),
                snapshotHandler = handler,
                domainEventBus = domainEventBus,
            )
        snapshotDispatcher.run()
        val aggregateId = aggregateMetadata.asAggregateId()
        val createdEventStream = AggregateCreated("")
            .asDomainEventStream(GivenInitializationCommand(aggregateId), 0)
        eventStore.append(createdEventStream).block()
        domainEventBus.send(createdEventStream).block()
        waitForAppend.asMono().block()
        snapshotRepository.load<MockAggregate>(aggregateId)
            .test()
            .expectNextCount(1)
            .verifyComplete()

        snapshotDispatcher.close()
    }
}
