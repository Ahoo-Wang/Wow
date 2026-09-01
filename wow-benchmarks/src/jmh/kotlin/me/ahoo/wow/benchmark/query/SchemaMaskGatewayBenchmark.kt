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

import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class SchemaMaskGatewayBenchmark {
    @Param("1", "64")
    var maskedFieldCount: Int = 0

    @Param("1", "1000")
    var resultCount: Int = 0

    private val namedAggregate = MaterializedNamedAggregate("benchmark-query", "schema-mask")
    private lateinit var gateway: SnapshotQueryGateway<ObjectNode>
    private lateinit var query: IListQuery

    @Setup
    fun setup() {
        val schema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = (0 until maskedFieldCount).associate { index ->
                LogicalField("state.secret$index") to maskedFieldSchema()
            },
        )
        val results = List(resultCount) {
            JsonNodeFactory.instance.objectNode().also { node ->
                node.putObject("state").put("visible", "value")
            }
        }
        val backend = object :
            SnapshotQueryBackend by NoOpSnapshotQueryBackend(namedAggregate),
            QueryModelSchemaProvider {
            private val schemaMono = Mono.just(schema)

            override fun schema(): Mono<QueryModelSchema> = schemaMono

            override fun refresh(): Mono<QueryModelSchema> = schemaMono

            override fun list(query: IListQuery): Flux<ObjectNode> = Flux.fromIterable(results)
        }
        gateway = DefaultSnapshotQueryGateway(
            namedAggregate = namedAggregate,
            backend = backend,
            targetType = JsonSerializer.typeFactory.constructType(ObjectNode::class.java),
        )
        query = ListQuery(MatchAllFilter, limit = resultCount)
    }

    @Benchmark
    fun maskMissingFields(blackhole: Blackhole) {
        blackhole.consume(gateway.dynamicList(query).collectList().block())
    }

    private fun maskedFieldSchema(): QueryFieldSchema {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = true,
            required = false,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = emptyMap(),
            maskRule = MaskRule(
                FullMaskStrategy::class,
                annotation,
                FullMaskStrategy.compile(annotation),
            ),
        )
    }

    private data class Masked(@field:Mask val secret: String)
}
