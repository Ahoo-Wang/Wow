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

import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class EventStreamFactoryBenchmark {

    @Benchmark
    fun createEventStream(blackhole: Blackhole) {
        val eventStream = BenchmarkEvents.singleEventStream()
        blackhole.consume(eventStream)
    }

    @Benchmark
    fun createSingleBodyEventStream(blackhole: Blackhole) {
        val eventStream = BenchmarkEvents.singleBodyEventStream()
        blackhole.consume(eventStream)
    }
}
