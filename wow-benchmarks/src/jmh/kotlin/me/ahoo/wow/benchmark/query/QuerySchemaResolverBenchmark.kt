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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
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
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
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
    private val stringValue = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
    private val modelNoneSchema = fieldSchema(
        identityField, identityField, QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE, QueryCapability.SORT,
    )
    private val modelNoneQuery = SingleQuery(
        EqualFilter(identityField, value),
        Projection(include = listOf(identityField)),
        listOf(Sort(identityField, Sort.Direction.ASC)),
    )
    private val fieldInferSchema = fieldSchema(mappedSortField, QueryField("document.createdAt"), QueryCapability.SORT)
    private val fieldInferQuery = SingleQuery(MatchAllFilter, sort = listOf(Sort(mappedSortField, Sort.Direction.ASC)))
    private val fieldRequiredSchema = fieldSchema(
        mappedFilterField, QueryField("document.status.keyword"), QueryCapability.EXACT_MATCH,
    )
    private val fieldRequiredFilter = EqualFilter(mappedFilterField, value)
    private val identityDynamicSchema = dynamicSchema("state", true)
    private val rewriteDynamicSchema = dynamicSchema("document", false)
    private val dynamicFilter = EqualFilter(QueryField("state.dynamic.code"), value)
    private val projectionSchema = fieldSchema(identityField, identityField, QueryCapability.PRESENCE)
    private val projection = Projection(include = listOf(identityField))
    private val projectionQuery = SingleQuery(MatchAllFilter, projection)

    @Setup
    fun verifyEquivalentInputs() {
        check(modelNoneSchema.field(identityField)!!.capabilities == setOf(QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE, QueryCapability.SORT))
        check(fieldInferSchema.field(mappedSortField)!!.capabilities == setOf(QueryCapability.SORT))
        check(fieldRequiredSchema.field(mappedFilterField)!!.capabilities == setOf(QueryCapability.EXACT_MATCH))
        check(fieldInferSchema.physicalField(mappedSortField, QueryCapability.SORT).path == "document.createdAt")
        check(fieldRequiredSchema.physicalField(mappedFilterField, QueryCapability.EXACT_MATCH).path == "document.status.keyword")
        check(identityDynamicSchema.definition.root.properties.getValue("state").properties.size == 4)
        check(rewriteDynamicSchema.definition.root.properties.getValue("state").properties.size == 1)
        check(identityDynamicSchema.physicalField(dynamicFilter.field, QueryCapability.EXACT_MATCH) == dynamicFilter.field)
        check(rewriteDynamicSchema.physicalField(dynamicFilter.field, QueryCapability.EXACT_MATCH).path == "document.dynamic.code")
        check(projectionSchema.field(identityField)!!.capabilities == setOf(QueryCapability.PRESENCE))
        check(listOf(modelNoneSchema, fieldInferSchema, fieldRequiredSchema, projectionSchema).all {
            it.definition.root.properties.getValue("state").properties.size == 1 && it.field(QueryField("deleted")) == null
        })
    }

    @Benchmark
    fun modelNoneIdentityQuery(blackhole: Blackhole) {
        val accepted = validateQuery(modelNoneQuery, modelNoneSchema)
        check(accepted === modelNoneQuery)
        blackhole.consume(accepted)
    }

    @Benchmark
    fun fieldInferMappedSort(blackhole: Blackhole) {
        blackhole.consume(validateQuery(fieldInferQuery, fieldInferSchema))
    }

    @Benchmark
    fun fieldRequiredMappedFilter(blackhole: Blackhole) {
        blackhole.consume(validateQuery(fieldRequiredFilter, fieldRequiredSchema))
    }

    @Benchmark
    fun identityDynamicFilter(blackhole: Blackhole) {
        val accepted = validateQuery(dynamicFilter, identityDynamicSchema)
        check(accepted === dynamicFilter)
        blackhole.consume(accepted)
    }

    @Benchmark
    fun rewriteDynamicFilter(blackhole: Blackhole) {
        blackhole.consume(validateQuery(dynamicFilter, rewriteDynamicSchema))
    }

    @Benchmark
    fun projectionValidationPassThrough(blackhole: Blackhole) {
        val accepted = validateQuery(projectionQuery, projectionSchema)
        check(accepted.projection === projection)
        blackhole.consume(accepted)
    }

    private fun fieldSchema(
        field: QueryField,
        physical: QueryField,
        vararg capabilities: QueryCapability,
    ): QueryModelSchema {
        val path = BenchmarkQuerySchemas.path(field.path)
        val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "state" to QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(field.path.substringAfterLast('.') to stringValue)),
        )))
        return QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition, mapOf(path to QueryValueBindings(
            bindings = capabilities.associateWith { QueryFieldBindingTemplate(BenchmarkQuerySchemas.path(physical.path), null) },
            projectionPath = path,
            responsePath = path,
        )))
    }

    private fun dynamicSchema(physicalRoot: String, unrelatedElements: Boolean): QueryModelSchema {
        val properties = buildMap {
            put("dynamic", QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = stringValue))
            if (unrelatedElements) listOf("orders", "shipments", "invoices").forEach { name ->
                put(name, QueryValueSchema(QueryValueKind.ARRAY, items = QueryValueSchema(QueryValueKind.OBJECT)))
            }
        }
        val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(
            "state" to QueryValueSchema(QueryValueKind.OBJECT, properties = properties),
        )))
        return QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), definition,
            definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
                val physical = QueryPathTemplate(path.segments.mapIndexed { index, part ->
                    if (index == 0) QueryPathSegment.Property(physicalRoot) else part
                })
                val capabilities = when (value.kind) {
                    QueryValueKind.SCALAR -> setOf(QueryCapability.EXACT_MATCH)
                    QueryValueKind.ARRAY -> setOf(QueryCapability.ELEMENT_SCOPE)
                    else -> emptySet()
                }
                QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(physical, null) }, path, path)
            })
    }
}
