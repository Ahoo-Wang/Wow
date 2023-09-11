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

import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.state.SimpleStateEventExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.asStateEvent
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

abstract class SnapshotStrategySpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    protected val snapshotStrategy: SnapshotStrategy

    init {
        this.snapshotStrategy = createSnapshotStrategy().metrizable()
    }

    abstract fun createSnapshotStrategy(): SnapshotStrategy

    @Test
    fun onEvent() {
        val aggregateId = aggregateMetadata.asAggregateId()
        val createdEventStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .asDomainEventStream(GivenInitializationCommand(aggregateId), 0)
        val state = MockStateAggregate(createdEventStream.aggregateId.id)
        val stateEvent = createdEventStream.asStateEvent(state)
        val exchange = SimpleStateEventExchange(stateEvent)
        snapshotStrategy.onEvent(exchange)
            .test()
            .verifyComplete()
    }
}
