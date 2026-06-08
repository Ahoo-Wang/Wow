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

package me.ahoo.wow.runtime

import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Benchmark)
open class EventStreamCopyBenchmark {

    private val eventStream: DomainEventStream = BenchmarkEvents.singleEventStream()

    @Benchmark
    fun copyEventStreamByJsonRoundTrip(): DomainEventStream {
        return eventStream.toJsonString().toObject<DomainEventStream>()
    }

    @Benchmark
    fun copyEventStreamWithDomainCopy(): DomainEventStream {
        return eventStream.copy()
    }

    @Benchmark
    fun convertEventStreamToMap(): Map<String, Any> {
        return eventStream.toLinkedHashMap()
    }

}
