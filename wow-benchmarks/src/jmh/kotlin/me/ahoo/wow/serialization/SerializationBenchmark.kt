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

package me.ahoo.wow.serialization

import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class SerializationBenchmark {
    private val commandMessage = BenchmarkCommands.fixedAggregateAddCartItem()
    private val eventStream: DomainEventStream = BenchmarkEvents.singleEventStream()

    @Benchmark
    fun commandSerializeDeserialize(blackhole: Blackhole) {
        val json = commandMessage.toJsonString()
        val deserialized = json.toObject<me.ahoo.wow.api.command.CommandMessage<*>>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun eventStreamSerializeDeserialize(blackhole: Blackhole) {
        val json = eventStream.toJsonString()
        val deserialized = json.toObject<DomainEventStream>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun commandSerialize(blackhole: Blackhole) {
        val json = commandMessage.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun eventStreamSerialize(blackhole: Blackhole) {
        val json = eventStream.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun commandDeserialize(blackhole: Blackhole) {
        val deserialized = preSerializedCommand.toObject<me.ahoo.wow.api.command.CommandMessage<*>>()
        blackhole.consume(deserialized)
    }

    @Benchmark
    fun eventStreamDeserialize(blackhole: Blackhole) {
        val deserialized = preSerializedEventStream.toObject<DomainEventStream>()
        blackhole.consume(deserialized)
    }

    private val preSerializedCommand by lazy { commandMessage.toJsonString() }
    private val preSerializedEventStream by lazy { eventStream.toJsonString() }
}
