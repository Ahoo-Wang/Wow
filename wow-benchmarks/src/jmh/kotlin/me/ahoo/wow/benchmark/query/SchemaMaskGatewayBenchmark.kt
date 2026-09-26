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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
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
        val schema = BenchmarkQuerySchemas.create(
            QueryModel.SNAPSHOT,
            (0 until maskedFieldCount).associate { index -> QueryField("state.secret$index") to maskedFieldSchema() },
            emptyMap(),
        )
        val backend = object : SnapshotQueryBackend {
            override val name: String = "benchmark"
            override val namedAggregate: NamedAggregate = this@SchemaMaskGatewayBenchmark.namedAggregate
            override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
            override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.range(0, resultCount).map {
                JsonNodeFactory.instance.objectNode().also { node ->
                    node.putObject("state").put("visible", "value")
                }
            }
            override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
                Mono.just(BackendPage(emptyList()))
            override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0L)
            override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
                Flux.empty()
        }
        val schemaProvider = object : QueryModelSchemaProvider {
            private val schemaMono = Mono.just(schema)

            override fun schema(): Mono<QueryModelSchema> = schemaMono

            override fun refresh(): Mono<QueryModelSchema> = schemaMono
        }
        gateway = DefaultSnapshotQueryGateway(
            namedAggregate = namedAggregate,
            backend = backend,
            schemaProvider = schemaProvider,
            targetType = JsonSerializer.typeFactory.constructType(ObjectNode::class.java),
        )
        query = ListQuery(MatchAllFilter, limit = resultCount)
        check(schema.definition.values.values.count { it.maskRule != null } == maskedFieldCount)
        val probe = checkNotNull(gateway.dynamicList(query).collectList().block())
        check(probe.size == resultCount)
        check(probe.all { it.path("state").size() == 1 && it.path("state").path("visible").asString() == "value" })
    }

    @Benchmark
    fun maskMissingFields(blackhole: Blackhole) {
        blackhole.consume(gateway.dynamicList(query).collectList().block())
    }

    private fun maskedFieldSchema(): me.ahoo.wow.query.schema.QueryValueSchema {
        return me.ahoo.wow.query.schema.QueryValueSchema(
            kind = me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR,
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = true,
            required = false,
            semanticType = null,
            maskRule = MaskRule(SensitivityLevel.DISPLAY),
        )
    }

}
