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

package me.ahoo.wow.commandpath

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.InMemoryDomainEventBus
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class CommandEventPublishBenchmark {
    private lateinit var eventBus: InMemoryDomainEventBus
    private val eventStream = BenchmarkEvents.singleEventStream()

    @Setup
    fun setup() {
        eventBus = InMemoryDomainEventBus()
        eventBus.receive(setOf(BenchmarkAggregates.namedAggregate)).subscribe()
    }

    @TearDown
    fun tearDown() {
        eventBus.close()
    }

    @Benchmark
    fun publishDomainEventStream(blackhole: Blackhole) {
        val result = eventBus.send(eventStream).block()
        blackhole.consume(result)
    }
}
