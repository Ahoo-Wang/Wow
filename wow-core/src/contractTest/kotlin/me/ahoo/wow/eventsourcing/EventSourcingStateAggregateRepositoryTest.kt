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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.tck.eventsourcing.StateAggregateRepositorySpec
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

internal class EventSourcingStateAggregateRepositoryTest : StateAggregateRepositorySpec() {
    override fun createStateAggregateRepository(
        aggregateFactory: StateAggregateFactory,
        eventStore: EventStore
    ): StateAggregateRepository {
        return EventSourcingStateAggregateRepository(
            aggregateFactory,
            InMemorySnapshotStore(),
            eventStore,
        )
    }

    @Test
    fun `should not create empty aggregate when snapshot exists`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        val snapshot = SimpleSnapshot(stateAggregate)
        val snapshotStore = object : SnapshotStore {
            override val name: String = "snapshot"

            @Suppress("UNCHECKED_CAST")
            override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
                Mono.just(snapshot as Snapshot<S>)

            override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> = Mono.empty()

            override fun scanAggregateId(
                namedAggregate: NamedAggregate,
                afterId: String,
                limit: Int
            ): Flux<AggregateId> = Flux.empty()
        }
        val createCount = AtomicInteger()
        val stateAggregateFactory = object : StateAggregateFactory {
            override fun <S : Any> create(
                metadata: StateAggregateMetadata<S>,
                aggregateId: AggregateId
            ): StateAggregate<S> {
                createCount.incrementAndGet()
                return ConstructorStateAggregateFactory.create(metadata, aggregateId)
            }
        }
        val repository = EventSourcingStateAggregateRepository(
            stateAggregateFactory,
            snapshotStore,
            InMemoryEventStore(),
        )

        repository.load(aggregateId, MOCK_AGGREGATE_METADATA.state).block()

        createCount.get().assert().isEqualTo(0)
    }
}
