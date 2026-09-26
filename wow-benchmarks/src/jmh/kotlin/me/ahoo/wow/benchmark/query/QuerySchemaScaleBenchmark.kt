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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.query.schema.validateQuery
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
    private lateinit var schema: QueryModelSchema
    private lateinit var definition: LogicalQuerySchema
    private lateinit var bindings: Map<QueryPathTemplate, QueryValueBindings>
    private lateinit var hit: QueryField
    private val missing = QueryField("state.missing.code")
    private var staticCount = 0
    private var dynamicCount = 0

    @Setup
    fun setup() {
        val count = shape.filter(Char::isDigit).toInt()
        staticCount = if (shape.startsWith("dynamic")) 32 else count
        dynamicCount = when {
            shape.startsWith("none") -> 0
            shape.startsWith("dynamic") -> count
            else -> 1
        }
        val prepared = scaleSchemaInputs(staticCount, dynamicCount)
        definition = prepared.first
        bindings = prepared.second
        schema = constructSchema()
        val suffix = if (dynamicCount <= 1) "dynamic.code" else "dynamic.branch${dynamicCount - 1}.code"
        hit = if (dynamicCount == 0) QueryField("state.field0") else QueryField("state.$suffix")
        val expected = if (dynamicCount == 0) hit else QueryField("storage.$suffix")
        check(schema.physicalField(hit, QueryCapability.EXACT_MATCH) == expected)
        check(schema.physicalField(QueryField("state.field0"), QueryCapability.EXACT_MATCH) == QueryField("state.field0"))
        check(bindings.values.count { it.bindings.isNotEmpty() } == staticCount + dynamicCount)
        check(bindings.values.filter { it.bindings.isNotEmpty() }.all { it.bindings.keys == SCALE_CAPABILITIES })
        val state = definition.root.properties.getValue("state")
        check(state.properties.size == staticCount + (if (dynamicCount == 0) 0 else 1))
        if (dynamicCount > 0) {
            val dynamic = state.properties.getValue("dynamic")
            check(dynamic.additionalProperties != null && dynamic.properties.size == dynamicCount - 1)
        }
        check(schema.field(missing) == null)
    }

    @Benchmark
    fun physicalHit(): QueryField = schema.physicalField(hit, QueryCapability.EXACT_MATCH)

    @Benchmark
    fun fieldMiss(): QueryFieldSchema? = schema.field(missing)

    @Benchmark
    fun constructSchema(): QueryModelSchema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition, bindings)

    /** Target-only: the old constructor benchmark excludes preparing its logical/native input records. */
    @Benchmark
    fun fullConstruction(): QueryModelSchema {
        val (logical, native) = scaleSchemaInputs(staticCount, dynamicCount)
        return QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), logical, native)
    }
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
        val (logical, native) = scaleSchemaInputs(width, 0)
        schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), logical, native)
        val fields = (0 until width).map { QueryField("state.field$it") }
        projectionQuery = SingleQuery(MatchAllFilter, Projection(include = fields))
        aggregationQuery = AggregationQuery(metrics = fields.mapIndexed { index, field ->
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
        check(aggregationQuery.metrics.count { it is AggregationMetric.Any } == (width + 1) / 2)
        check(aggregationQuery.metrics.filterIsInstance<AggregationMetric.Numeric>().all {
            it.function == AggregationFunction.SUM && it.expression is AggregationExpression.Binary
        })
        check(native.values.filter { it.bindings.isNotEmpty() }.all { it.bindings.keys == SCALE_CAPABILITIES })
        check(validateQuery(projectionQuery, schema) === projectionQuery)
        check(validateQuery(aggregationQuery, schema) === aggregationQuery)
    }

    @Benchmark
    fun projection(): ISingleQuery = validateQuery(projectionQuery, schema)

    @Benchmark
    fun aggregation(): AggregationQuery = validateQuery(aggregationQuery, schema)
}

private val SCALE_CAPABILITIES = setOf(
    QueryCapability.EXACT_MATCH,
    QueryCapability.PRESENCE,
    QueryCapability.AGGREGATE_TERMS,
    QueryCapability.AGGREGATE_NUMERIC,
)

/** Same declared fields and map layout as the frozen flat input; preparation stays outside constructSchema. */
private fun scaleSchemaInputs(
    staticCount: Int,
    dynamicCount: Int,
): Pair<LogicalQuerySchema, Map<QueryPathTemplate, QueryValueBindings>> {
    val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
    val properties = buildMap {
        repeat(staticCount) { put("field$it", scalar) }
        if (dynamicCount > 0) {
            put(
                "dynamic",
                QueryValueSchema(
                    QueryValueKind.OBJECT,
                    additionalProperties = scalar,
                    properties = (1 until dynamicCount).associate { index ->
                        "branch$index" to QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = scalar)
                    },
                ),
            )
        }
    }
    val definition = LogicalQuerySchema(
        QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf("state" to QueryValueSchema(QueryValueKind.OBJECT, properties = properties)),
        ),
    )
    val bindings = definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
        val dynamic = path.segments.getOrNull(1) == QueryPathSegment.Property("dynamic")
        val physical = if (dynamic) {
            QueryPathTemplate(listOf(QueryPathSegment.Property("storage")) + path.segments.drop(1))
        } else {
            path
        }
        QueryValueBindings(
            bindings = if (value.kind == QueryValueKind.SCALAR) {
                SCALE_CAPABILITIES.associateWith { QueryFieldBindingTemplate(physical, null) }
            } else {
                emptyMap()
            },
            projectionPath = path,
            responsePath = path,
        )
    }
    return definition to bindings
}
