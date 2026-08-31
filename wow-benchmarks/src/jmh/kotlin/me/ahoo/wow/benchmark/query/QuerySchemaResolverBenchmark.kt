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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.resolve
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

private const val MASKED_FIELD_COUNT = 64
private const val FILTER_OPERAND_COUNT = 8

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class QuerySchemaResolverBenchmark {
    private val sortableField = LogicalField("state.createdAt")
    private val schema = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = buildMap {
            putAll(
                List(MASKED_FIELD_COUNT) { index ->
                    LogicalField("state.secret$index") to maskedFieldSchema()
                },
            )
            putAll(
                List(FILTER_OPERAND_COUNT) { index ->
                    LogicalField("state.filter$index") to maskedFieldSchema().copy(
                        bindings = mapOf(
                            QueryCapability.EXACT_MATCH to QueryFieldBinding("document.filter$index", null),
                        ),
                        maskRule = null,
                    )
                },
            )
            put(
                sortableField,
                maskedFieldSchema().copy(
                    bindings = mapOf(
                        QueryCapability.SORT to QueryFieldBinding("document.createdAt", null),
                    ),
                    maskRule = null,
                ),
            )
        },
    )
    private val provider = object : QueryModelSchemaProvider {
        private val schemaMono = Mono.just(schema)

        override fun schema(): Mono<QueryModelSchema> = schemaMono

        override fun refresh(): Mono<QueryModelSchema> = schemaMono
    }
    private val query = SingleQuery(MatchAllFilter)
    private val compositeFilterQuery = SingleQuery(
        AndFilter(
            List(FILTER_OPERAND_COUNT) { index ->
                EqualFilter(
                    LogicalField("state.filter$index"),
                    JsonNodeFactory.instance.stringNode(index.toString()),
                )
            },
        ),
    )
    private val cursorQuery = CursorQuery(
        MatchAllFilter,
        sort = listOf(Sort(sortableField.value, Sort.Direction.ASC)),
    )

    @Benchmark
    fun resolve(blackhole: Blackhole) {
        blackhole.consume(provider.resolve(query, QuerySchemaValidationMode.COMPATIBLE).block())
    }

    @Benchmark
    fun resolveCompositeFilter(blackhole: Blackhole) {
        blackhole.consume(provider.resolve(compositeFilterQuery, QuerySchemaValidationMode.COMPATIBLE).block())
    }

    @Benchmark
    fun resolveCursorSort(blackhole: Blackhole) {
        blackhole.consume(provider.resolve(cursorQuery, QuerySchemaValidationMode.COMPATIBLE).block())
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
