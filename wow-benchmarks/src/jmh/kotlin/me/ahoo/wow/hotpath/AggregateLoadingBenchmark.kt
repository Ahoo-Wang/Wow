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

package me.ahoo.wow.hotpath

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class AggregateLoadingBenchmark {
    private lateinit var repository: EventSourcingStateAggregateRepository
    private lateinit var aggregateId: AggregateId

    @Setup
    fun setup() {
        val eventStore = InMemoryEventStore()
        repository = EventSourcingStateAggregateRepository(
            ConstructorStateAggregateFactory,
            InMemorySnapshotRepository(),
            eventStore,
        )
        aggregateId = BenchmarkAggregates.aggregateId()
    }

    @TearDown
    fun tearDown() {
        setup()
    }

    @Benchmark
    fun loadEmptyAggregate(blackhole: Blackhole) {
        val aggregate = repository.load(
            aggregateId,
            BenchmarkAggregates.cartMetadata.state,
            Int.MAX_VALUE,
        ).block()
        blackhole.consume(aggregate)
    }
}
