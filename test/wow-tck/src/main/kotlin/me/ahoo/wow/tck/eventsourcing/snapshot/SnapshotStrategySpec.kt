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

package me.ahoo.wow.tck.eventsourcing.snapshot

import me.ahoo.wow.event.SimpleEventStreamExchange
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

abstract class SnapshotStrategySpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    val eventStore: EventStore
    private val snapshotStrategy: SnapshotStrategy

    init {
        this.eventStore = createEventStore().metrizable()
        this.snapshotStrategy = createSnapshotStrategy().metrizable()
    }

    open fun createEventStore(): EventStore {
        return InMemoryEventStore()
    }

    abstract fun createSnapshotStrategy(): SnapshotStrategy

    @Test
    fun onEvent() {
        val aggregateId = aggregateMetadata.asAggregateId()
        val createdEventStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .asDomainEventStream(GivenInitializationCommand(aggregateId), 0)
        eventStore.append(createdEventStream).block()
        val createdEventStreamExchange = SimpleEventStreamExchange(createdEventStream)
        snapshotStrategy.onEvent(createdEventStreamExchange)
            .test()
            .verifyComplete()

        val changedEventStream = MockAggregateChanged(GlobalIdGenerator.generateAsString())
            .asDomainEventStream(GivenInitializationCommand(aggregateId), 1)
        val changedEventStreamExchange = SimpleEventStreamExchange(changedEventStream)
        eventStore.append(changedEventStream).block()
        snapshotStrategy.onEvent(changedEventStreamExchange)
            .test()
            .verifyComplete()

        snapshotStrategy.onEvent(changedEventStreamExchange)
            .test()
            .verifyComplete()

        val changedEventStream2 = MockAggregateChanged(GlobalIdGenerator.generateAsString())
            .asDomainEventStream(GivenInitializationCommand(aggregateId), 2)
        eventStore.append(changedEventStream2).block()

        val changedEventStream3 = MockAggregateChanged(GlobalIdGenerator.generateAsString())
            .asDomainEventStream(GivenInitializationCommand(aggregateId), 3)
        val changedEventStreamExchange3 = SimpleEventStreamExchange(changedEventStream3)
        eventStore.append(changedEventStream3).block()
        snapshotStrategy.onEvent(changedEventStreamExchange3)
            .test()
            .verifyComplete()
    }
}
