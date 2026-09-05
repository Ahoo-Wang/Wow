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

import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaResolution
import me.ahoo.wow.api.query.schema.QueryModel
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
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class QueryFieldResolutionScaleBenchmark {
    @Param("static32", "static256", "static2048", "dynamic1", "dynamic16", "dynamic128", "none32", "none2048")
    lateinit var shape: String
    private lateinit var fields: Map<QueryField, QueryFieldSchema>
    private lateinit var schema: QueryModelSchema
    private lateinit var hit: QueryField
    private val missing = QueryField("document.missing.code")

    @Setup
    fun setup() {
        val count = shape.filter(Char::isDigit).toInt()
        val staticCount = if (shape.startsWith("dynamic")) 32 else count
        val dynamicCount = when {
            shape.startsWith("none") -> 0
            shape.startsWith("dynamic") -> count
            else -> 1
        }
        fields = buildMap {
            repeat(staticCount) { index ->
                val path = "state.field$index"
                put(QueryField(path), benchmarkField(path))
            }
            repeat(dynamicCount) { index ->
                val suffix = if (index == 0) "dynamic" else "dynamic.branch$index"
                val path = "state.$suffix"
                put(QueryField(path), benchmarkField(path, "document.$suffix", "storage.$suffix", true))
            }
        }
        schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
        val suffix = if (dynamicCount <= 1) "dynamic.code" else "dynamic.branch${dynamicCount - 1}.code"
        hit = if (dynamicCount == 0) QueryField("state.field0") else QueryField("document.$suffix")
        val expected = if (dynamicCount == 0) hit else QueryField("storage.$suffix")
        check(schema.resolvePhysicalField(hit, QueryCapability.EXACT_MATCH) == expected)
        check(schema.resolvePhysicalField(missing, QueryCapability.EXACT_MATCH) == missing)
    }

    @Benchmark
    fun physicalHit(): QueryField = schema.resolvePhysicalField(hit, QueryCapability.EXACT_MATCH)

    @Benchmark
    fun physicalMiss(): QueryField = schema.resolvePhysicalField(missing, QueryCapability.EXACT_MATCH)

    @Benchmark
    fun constructSchema(): QueryModelSchema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class QueryComponentResolutionBenchmark {
    @Param("1", "16", "64")
    var width: Int = 1

    private lateinit var schema: QueryModelSchema
    private lateinit var projectionQuery: SingleQuery
    private lateinit var aggregationQuery: AggregationQuery

    @Setup
    fun setup() {
        val fields = (0 until width).associate { index ->
            val path = "state.field$index"
            QueryField(path) to benchmarkField(path)
        }
        schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
        projectionQuery = SingleQuery(MatchAllFilter, Projection(include = fields.keys.toList()))
        aggregationQuery = AggregationQuery(metrics = fields.keys.mapIndexed { index, field ->
            if (index % 2 == 0) {
                AggregationMetric.Any(field, "metric$index")
            } else {
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(field),
                        AggregationExpression.Constant(1.0),
                    ),
                    "metric$index",
                )
            }
        })
        check(schema.resolve(projectionQuery).compatibility == QueryCompatibilityLevel.EXACT)
        check(schema.resolve(aggregationQuery).compatibility == QueryCompatibilityLevel.EXACT)
    }

    @Benchmark
    fun projection(): QuerySchemaResolution<ISingleQuery> = schema.resolve(projectionQuery)

    @Benchmark
    fun aggregation(): QuerySchemaResolution<AggregationQuery> = schema.resolve(aggregationQuery)
}

private fun benchmarkField(
    path: String,
    resolved: String = path,
    physical: String = resolved,
    dynamic: Boolean = false,
): QueryFieldSchema = QueryFieldSchema(
    title = null,
    description = null,
    enumValues = null,
    valueTypes = emptySet(),
    nullable = true,
    required = false,
    cardinality = QueryCardinality.SINGLE,
    semanticType = null,
    dynamicChildren = dynamic,
    bindings = setOf(
        QueryCapability.EXACT_MATCH,
        QueryCapability.PRESENCE,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
    ).associateWith { QueryFieldBinding(QueryField(resolved), QueryField(physical), null) },
    rewriteMode = if (path == resolved) QueryRewriteMode.NONE else QueryRewriteMode.REQUIRED,
)
