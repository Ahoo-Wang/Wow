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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.SimpleStateAggregate
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class AggregateLoadComponentBenchmark {
    @Param("10", "50", "100", "500")
    var eventCount: Int = 10

    private lateinit var repository: EventSourcingStateAggregateRepository
    private lateinit var emptyAggregateId: AggregateId
    private lateinit var recoveryAggregateId: AggregateId
    private lateinit var eventStreams: List<DomainEventStream>
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var snapshotAggregateId: AggregateId

    @Setup
    fun setup() {
        repository = EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            InMemoryEventStore(),
        )
        emptyAggregateId = BenchmarkAggregates.aggregateId()
        recoveryAggregateId = BenchmarkAggregates.aggregateId()
        eventStreams = BenchmarkEvents.eventStreams(recoveryAggregateId, eventCount)
        snapshotRepository = InMemorySnapshotRepository()
        snapshotAggregateId = BenchmarkAggregates.aggregateId()
        val aggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            snapshotAggregateId,
        )
        val snapshot = SimpleSnapshot(BenchmarkEvents.singleEventStream(snapshotAggregateId).toStateEvent(aggregate))
        snapshotRepository.save(snapshot).block()
    }

    @Benchmark
    fun loadEmptyStateAggregate(blackhole: Blackhole) {
        val aggregate = repository.load(
            emptyAggregateId,
            BenchmarkAggregates.cartMetadata.state,
            Int.MAX_VALUE,
        ).block()
        blackhole.consume(aggregate)
    }

    @Benchmark
    fun recoverFromEvents(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            recoveryAggregateId,
        )
        for (eventStream in eventStreams) {
            aggregate.onSourcing(eventStream)
        }
        blackhole.consume(aggregate)
    }

    @Benchmark
    fun loadSnapshot(blackhole: Blackhole) {
        val snapshot = checkNotNull(
            snapshotRepository.load<SimpleStateAggregate<*>>(snapshotAggregateId).block()
        )
        blackhole.consume(snapshot)
    }
}
