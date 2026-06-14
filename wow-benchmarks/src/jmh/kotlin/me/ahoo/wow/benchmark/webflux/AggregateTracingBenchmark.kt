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

package me.ahoo.wow.benchmark.webflux

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunction.Companion.trace
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class AggregateTracingBenchmark {
    @Param("1", "10", "100")
    var eventCount: Int = 1

    private lateinit var eventStreams: List<DomainEventStream>

    @Setup(Level.Iteration)
    fun setup() {
        eventStreams = BenchmarkEvents.eventStreams(eventCount = eventCount)
    }

    @Benchmark
    fun traceCartHistory(blackhole: Blackhole) {
        val tracedStates = BenchmarkAggregates.cartMetadata.state.trace(
            ConstructorStateAggregateFactory,
            eventStreams,
        )
        blackhole.consume(tracedStates)
    }
}
