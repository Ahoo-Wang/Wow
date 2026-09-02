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

package me.ahoo.wow.benchmark.query

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.FilterNormalizer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class FilterNormalizerBenchmark {
    @Param("matchAll", "and1", "and8", "and32")
    lateinit var shape: String

    private val normalizer = FilterNormalizer(
        clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
        defaultZoneId = ZoneOffset.UTC,
        defaultDeletionState = DeletionState.ACTIVE,
    )
    private lateinit var filter: FilterExpression

    @Setup(Level.Trial)
    fun setup() {
        filter = when (shape) {
            "matchAll" -> MatchAllFilter
            "and1" -> filter(1)
            "and8" -> filter(8)
            "and32" -> filter(32)
            else -> error("Unsupported filter shape: $shape")
        }
    }

    @Benchmark
    fun normalize(blackhole: Blackhole) {
        blackhole.consume(normalizer.normalize(filter))
    }

    private fun filter(size: Int): FilterExpression = AndFilter(
        List(size) { index ->
            EqualFilter(
                QueryField("state.field$index"),
                JsonNodeFactory.instance.numberNode(index),
            )
        },
    )
}
