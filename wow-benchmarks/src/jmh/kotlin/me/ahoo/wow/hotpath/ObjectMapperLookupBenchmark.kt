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

import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

data class SmallPayload(val name: String = "test", val value: Int = 42)

@State(Scope.Benchmark)
open class ObjectMapperLookupBenchmark {
    private val payload = SmallPayload()
    private val preSerialized = payload.toJsonString()

    @Benchmark
    fun serialize(blackhole: Blackhole) {
        val json = payload.toJsonString()
        blackhole.consume(json)
    }

    @Benchmark
    fun deserialize(blackhole: Blackhole) {
        val obj = preSerialized.toObject<SmallPayload>()
        blackhole.consume(obj)
    }

    @Benchmark
    fun roundTrip(blackhole: Blackhole) {
        val json = payload.toJsonString()
        val obj = json.toObject<SmallPayload>()
        blackhole.consume(obj)
    }

    @Benchmark
    fun serializeWithSharedMapper(blackhole: Blackhole) {
        val json = JsonSerializer.writeValueAsString(payload)
        blackhole.consume(json)
    }
}
