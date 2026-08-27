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

import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class EventStoreComponentBenchmark {
    private companion object {
        const val APPENDS_PER_INVOCATION = 1024
    }

    private lateinit var inMemoryEventStore: InMemoryEventStore
    private lateinit var inMemoryAppendEventStreams: List<DomainEventStream>
    private val eventStream: DomainEventStream = BenchmarkEvents.singleEventStream()

    @Setup
    fun setup() {
        inMemoryEventStore = InMemoryEventStore()
        inMemoryAppendEventStreams = List(APPENDS_PER_INVOCATION) {
            BenchmarkEvents.singleEventStream()
        }
    }

    @Benchmark
    fun createEventStream(blackhole: Blackhole) {
        blackhole.consume(BenchmarkEvents.singleEventStream())
    }

    @Benchmark
    @OperationsPerInvocation(APPENDS_PER_INVOCATION)
    fun appendInMemoryNewAggregateEventStream(blackhole: Blackhole) {
        for (appendEventStream in inMemoryAppendEventStreams) {
            val result = inMemoryEventStore.append(appendEventStream).block()
            blackhole.consume(result)
        }
        // The reset cost is intentionally amortized across APPENDS_PER_INVOCATION operations.
        inMemoryEventStore = InMemoryEventStore()
    }

    @Benchmark
    fun appendNoopEventStream(blackhole: Blackhole) {
        val result = NoopEventStore.append(eventStream).block()
        blackhole.consume(result)
    }

    @Benchmark
    fun copyEventStreamByJsonRoundTrip(blackhole: Blackhole) {
        val copied = eventStream.toJsonString().toObject<DomainEventStream>()
        blackhole.consume(copied)
    }

    @Benchmark
    fun copyEventStreamWithDomainCopy(blackhole: Blackhole) {
        blackhole.consume(eventStream.copy())
    }
}
