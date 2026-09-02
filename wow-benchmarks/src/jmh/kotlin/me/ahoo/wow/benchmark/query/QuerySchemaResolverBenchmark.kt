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

import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
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
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
open class QuerySchemaResolverBenchmark {
    private val value = JsonNodeFactory.instance.stringNode("value")
    private val identityField = QueryField("state.name")
    private val mappedSortField = QueryField("state.createdAt")
    private val mappedFilterField = QueryField("state.status")
    private val dynamicRoot = QueryField("state.dynamic")
    private val dynamicChild = QueryField("state.dynamic.code")
    private val unrelatedElementA = QueryField("state.orders")
    private val unrelatedElementB = QueryField("state.shipments")
    private val unrelatedElementC = QueryField("state.invoices")

    private val modelNoneSchema = schema(
        identityField to fieldSchema(
            bindings = setOf(
                QueryCapability.EXACT_MATCH,
                QueryCapability.PRESENCE,
                QueryCapability.SORT,
            ).associateWith { identityField.binding() },
            rewriteMode = QueryRewriteMode.NONE,
        ),
    )
    private val modelNoneQuery = SingleQuery(
        filter = EqualFilter(identityField, value),
        projection = Projection(include = listOf(identityField)),
        sort = listOf(Sort(identityField, Sort.Direction.ASC)),
    )

    private val fieldInferSchema = schema(
        mappedSortField to fieldSchema(
            bindings = mapOf(
                QueryCapability.SORT to mappedSortField.binding(QueryField("document.createdAt")),
            ),
            rewriteMode = QueryRewriteMode.INFER,
        ),
    )
    private val fieldInferQuery = SingleQuery(
        MatchAllFilter,
        sort = listOf(Sort(mappedSortField, Sort.Direction.ASC)),
    )

    private val fieldRequiredSchema = schema(
        mappedFilterField to fieldSchema(
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to mappedFilterField.binding(QueryField("document.status.keyword")),
            ),
            rewriteMode = QueryRewriteMode.REQUIRED,
        ),
    )
    private val fieldRequiredFilter = EqualFilter(mappedFilterField, value)

    private val identityDynamicSchema = schema(
        unrelatedElementA to fieldSchema(
            bindings = mapOf(QueryCapability.ELEMENT_SCOPE to unrelatedElementA.binding()),
            rewriteMode = QueryRewriteMode.INFER,
        ),
        unrelatedElementB to fieldSchema(
            bindings = mapOf(QueryCapability.ELEMENT_SCOPE to unrelatedElementB.binding()),
            rewriteMode = QueryRewriteMode.INFER,
        ),
        unrelatedElementC to fieldSchema(
            bindings = mapOf(QueryCapability.ELEMENT_SCOPE to unrelatedElementC.binding()),
            rewriteMode = QueryRewriteMode.INFER,
        ),
        dynamicRoot to fieldSchema(
            bindings = mapOf(QueryCapability.EXACT_MATCH to dynamicRoot.binding()),
            dynamicChildren = true,
            rewriteMode = QueryRewriteMode.NONE,
        ),
    )
    private val rewriteDynamicSchema = schema(
        dynamicRoot to fieldSchema(
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to dynamicRoot.binding(QueryField("document.dynamic")),
            ),
            dynamicChildren = true,
            rewriteMode = QueryRewriteMode.REQUIRED,
        ),
    )
    private val dynamicFilter = EqualFilter(dynamicChild, value)

    private val projectionSchema = schema(
        identityField to fieldSchema(
            bindings = mapOf(QueryCapability.PRESENCE to identityField.binding()),
            rewriteMode = QueryRewriteMode.NONE,
        ),
    )
    private val projection = Projection(include = listOf(identityField))
    private val projectionQuery = SingleQuery(MatchAllFilter, projection = projection)

    @Benchmark
    fun modelNoneIdentityQuery(blackhole: Blackhole) {
        val resolution = modelNoneSchema.resolve(modelNoneQuery)
        check(resolution.value === modelNoneQuery)
        blackhole.consume(resolution)
    }

    @Benchmark
    fun fieldInferMappedSort(blackhole: Blackhole) {
        blackhole.consume(fieldInferSchema.resolve(fieldInferQuery))
    }

    @Benchmark
    fun fieldRequiredMappedFilter(blackhole: Blackhole) {
        blackhole.consume(fieldRequiredSchema.resolve(fieldRequiredFilter))
    }

    @Benchmark
    fun identityDynamicFilter(blackhole: Blackhole) {
        val resolution = identityDynamicSchema.resolve(dynamicFilter)
        check(resolution.value === dynamicFilter)
        blackhole.consume(resolution)
    }

    @Benchmark
    fun rewriteDynamicFilter(blackhole: Blackhole) {
        blackhole.consume(rewriteDynamicSchema.resolve(dynamicFilter))
    }

    @Benchmark
    fun projectionValidationPassThrough(blackhole: Blackhole) {
        val resolution = projectionSchema.resolve(projectionQuery)
        check(resolution.value.projection === projection)
        blackhole.consume(resolution)
    }

    private fun schema(vararg fields: Pair<QueryField, QueryFieldSchema>) = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = mapOf(*fields),
    )

    private fun fieldSchema(
        bindings: Map<QueryCapability, QueryFieldBinding>,
        dynamicChildren: Boolean = false,
        rewriteMode: QueryRewriteMode,
    ) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(QueryValueType.STRING),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = dynamicChildren,
        bindings = bindings,
        rewriteMode = rewriteMode,
    )

    private fun QueryField.binding(resolvedField: QueryField = this) = QueryFieldBinding(
        resolvedField = resolvedField,
        physicalField = resolvedField,
        storageType = null,
    )
}
