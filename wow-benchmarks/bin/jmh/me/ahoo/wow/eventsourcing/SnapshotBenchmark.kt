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

import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.state.SimpleStateEventExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class SnapshotBenchmark {
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var snapshotStrategy: VersionOffsetSnapshotStrategy
    private lateinit var stateEventExchange: SimpleStateEventExchange<*>
    private lateinit var aggregateId: me.ahoo.wow.api.modeling.AggregateId

    @Setup
    fun setup() {
        aggregateId = cartAggregateMetadata.aggregateId()
        snapshotRepository = InMemorySnapshotRepository()
        snapshotStrategy = VersionOffsetSnapshotStrategy(
            versionOffset = 5,
            snapshotRepository = snapshotRepository,
        )

        val aggregate = ConstructorStateAggregateFactory.create(
            cartAggregateMetadata.state,
            aggregateId,
        )
        val snapshot = SimpleSnapshot(aggregate)
        snapshotRepository.save(snapshot).block()

        val eventStream = createEventStream()
        val stateEvent = eventStream.toStateEvent(aggregate)
        stateEventExchange = SimpleStateEventExchange(stateEvent)
    }

    @Benchmark
    fun snapshotStrategyEvaluate(blackhole: Blackhole) {
        val result = snapshotStrategy.onEvent(stateEventExchange).block()
        blackhole.consume(result)
    }

    @Benchmark
    fun snapshotLoad(blackhole: Blackhole) {
        val snapshot = snapshotRepository.load<me.ahoo.wow.modeling.state.SimpleStateAggregate<*>>(aggregateId).block()
        blackhole.consume(snapshot)
    }
}
