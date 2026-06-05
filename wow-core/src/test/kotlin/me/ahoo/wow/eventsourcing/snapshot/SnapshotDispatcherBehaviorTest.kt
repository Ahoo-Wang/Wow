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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.DefaultSnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotFunctionFilter
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.filter.FilterChainBuilder
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
import reactor.test.StepVerifier

class SnapshotDispatcherBehaviorTest {

    @Test
    fun `dispatcher receives state events and saves snapshots through handler chain`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("snapshot-dispatcher")
        val stateEventBus = InMemoryStateEventBus()
        val repository = SignalingSnapshotRepository()
        val chain = FilterChainBuilder<me.ahoo.wow.eventsourcing.state.StateEventExchange<*>>()
            .addFilter(SnapshotFunctionFilter(SimpleSnapshotStrategy(repository)))
            .filterCondition(SnapshotDispatcher::class)
            .build()
        val dispatcher = SnapshotDispatcher(
            name = "test.SnapshotDispatcher",
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA.materialize()),
            snapshotHandler = DefaultSnapshotHandler(chain),
            stateEventBus = stateEventBus,
        )
        val eventStream = MockAggregateCreated("created").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        )
        val stateEvent = eventStream.toStateEvent(MockStateAggregate(aggregateId.id))
        dispatcher.start()

        StepVerifier.create(repository.saved.asMono())
            .then {
                StepVerifier.create(stateEventBus.send(stateEvent)).verifyComplete()
            }
            .assertNext {
                it.aggregateId.assert().isEqualTo(aggregateId)
                it.version.assert().isEqualTo(1)
            }
            .verifyComplete()
        StepVerifier.create(repository.load<MockStateAggregate>(aggregateId))
            .assertNext {
                it.state.id.assert().isEqualTo("snapshot-dispatcher")
            }
            .verifyComplete()
        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
    }

    private class SignalingSnapshotRepository : SnapshotRepository {
        private val delegate = InMemorySnapshotRepository()
        val saved: Sinks.One<Snapshot<*>> = Sinks.one()

        override val name: String = "signaling"

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> = delegate.load(aggregateId)

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
            delegate.save(snapshot)
                .doOnSuccess {
                    saved.tryEmitValue(snapshot).orThrow()
                }

        override fun scanAggregateId(
            namedAggregate: NamedAggregate,
            afterId: String,
            limit: Int,
        ): Flux<AggregateId> = delegate.scanAggregateId(namedAggregate, afterId, limit)
    }
}
