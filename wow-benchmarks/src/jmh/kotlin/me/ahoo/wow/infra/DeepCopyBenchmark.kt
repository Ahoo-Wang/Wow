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

package me.ahoo.wow.infra

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.createEventStream
import me.ahoo.wow.serialization.deepCopy
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toMap
import me.ahoo.wow.serialization.toObject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup

@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 2, time = 5)
@Fork(value = 2)
@State(Scope.Benchmark)
@Threads(1)
open class DeepCopyBenchmark {

    private val eventStream: DomainEventStream = createEventStream()

    @Benchmark
    fun toJsonNodeToObject(): DomainEventStream {
        return eventStream.toJsonNode<ObjectNode>().toObject<DomainEventStream>()
    }

    @Benchmark
    fun convertValue(): DomainEventStream {
        return eventStream.deepCopy(DomainEventStream::class.java)
    }

    @Benchmark
    fun convertValueToMap(): Map<String, Any> {
        return eventStream.toMap()
    }

}