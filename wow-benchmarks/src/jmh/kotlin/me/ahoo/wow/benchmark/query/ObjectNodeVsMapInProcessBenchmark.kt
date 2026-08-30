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

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.serialization.JsonSerializer
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
import tools.jackson.databind.JavaType
import tools.jackson.databind.node.ObjectNode
import java.util.LinkedHashMap
import java.util.Random
import java.util.concurrent.TimeUnit

private const val IN_PROCESS_DATASET_SIZE = 1_000
private const val IN_PROCESS_DATASET_SEED = 20_260_829L

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class ObjectNodeVsMapInProcessBenchmark {
    @Param("single", "batch100", "batch1000")
    lateinit var operation: String

    @Param("decode", "materialize")
    lateinit var stage: String

    @Param("objectNode", "map")
    lateinit var representation: String

    private lateinit var source: List<ByteArray>
    private lateinit var objectNodes: List<ObjectNode>
    private lateinit var maps: List<Map<String, Any?>>
    private lateinit var snapshotType: JavaType
    private lateinit var mapType: JavaType

    @Setup(Level.Trial)
    fun setup() {
        require(operation in setOf("single", "batch100", "batch1000"))
        require(stage == "decode" || stage == "materialize")
        require(representation == "objectNode" || representation == "map")

        snapshotType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            QueryBenchmarkState::class.java,
        )
        mapType = JsonSerializer.typeFactory.constructMapType(
            LinkedHashMap::class.java,
            String::class.java,
            Any::class.java,
        )
        source = snapshots().map(JsonSerializer::writeValueAsBytes)
        objectNodes = source.map { JsonSerializer.readValue(it, ObjectNode::class.java) }
        maps = source.map { JsonSerializer.readValue<Map<String, Any?>>(it, mapType) }

        val probe = execute(0)
        check(
            if (stage == "materialize") {
                probe is MaterializedSnapshot<*>
            } else {
                when (representation) {
                "objectNode" -> probe is ObjectNode
                else -> probe is Map<*, *>
                }
            },
        )
    }

    @Benchmark
    fun execute(blackhole: Blackhole) {
        val size = when (operation) {
            "single" -> 1
            "batch100" -> 100
            "batch1000" -> 1_000
            else -> error("Unsupported operation: $operation")
        }
        if (size == 1) {
            blackhole.consume(execute(0))
            return
        }
        val results = ArrayList<Any>(size)
        repeat(size) { results += execute(it) }
        blackhole.consume(results)
    }

    private fun execute(index: Int): Any {
        return when (stage) {
            "decode" -> when (representation) {
                "objectNode" -> JsonSerializer.readValue(source[index], ObjectNode::class.java)
                "map" -> JsonSerializer.readValue<Map<String, Any?>>(source[index], mapType)
                else -> error("Unsupported representation: $representation")
            }

            "materialize" -> when (representation) {
                "objectNode" -> JsonSerializer.treeToValue<Any>(objectNodes[index], snapshotType)
                "map" -> JsonSerializer.convertValue<Any>(maps[index], snapshotType)
                else -> error("Unsupported representation: $representation")
            }

            else -> error("Unsupported stage: $stage")
        }
    }

    private fun snapshots(): List<MaterializedSnapshot<QueryBenchmarkState>> {
        val random = Random(IN_PROCESS_DATASET_SEED)
        return List(IN_PROCESS_DATASET_SIZE) { index ->
            val aggregateId = "query-benchmark-%04d".format(index)
            MaterializedSnapshot(
                contextName = "benchmark-query",
                aggregateName = "query_benchmark",
                tenantId = "benchmark",
                aggregateId = aggregateId,
                version = 1,
                eventId = "event-$aggregateId",
                firstOperator = "benchmark",
                operator = "benchmark",
                firstEventTime = 1_725_000_000_000L + index,
                eventTime = 1_725_000_000_000L + index,
                state = QueryBenchmarkState(
                    id = aggregateId,
                    group = index % 16,
                    payload = buildString(128) {
                        repeat(128) { append(('a'.code + random.nextInt(26)).toChar()) }
                    },
                ),
                snapshotTime = 1_725_000_001_000L + index,
                deleted = false,
            )
        }
    }
}
