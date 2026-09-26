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

import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
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

/** Measures generation construction separately from protection admission on a published generation. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@Threads(1)
open class SchemaProtectionBenchmark {
    @Param("16", "64", "256")
    var maskedFieldCount: Int = 0

    private lateinit var schema: QueryModelSchema
    private val query = CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("state.visible"), Sort.Direction.ASC)))

    @Setup
    fun setup() {
        val masked = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            maskRule = MaskRule(SensitivityLevel.DISPLAY),
        )
        val fields = (0 until maskedFieldCount).associate { QueryField("state.secret$it") to masked } +
            (QueryField("state.visible") to BenchmarkQuerySchemas.scalar(QueryValueType.STRING, null)) +
            // The record identity, which cursor admission appends as the unique tie-breaker.
            (QueryField("aggregateId") to BenchmarkQuerySchemas.scalar(QueryValueType.STRING, null))
        val caps = setOf(QueryCapability.EXACT_MATCH, QueryCapability.LITERAL_MATCH, QueryCapability.SORT, QueryCapability.CURSOR_SORT)
        schema = BenchmarkQuerySchemas.create(QueryModel.SNAPSHOT, fields, fields.keys.associateWith { field -> caps.associateWith { field } })
        check(schema.definition.values.values.count { it.maskRule != null } == maskedFieldCount)
        QueryAdmission.Trusted.cursor(query, schema)
    }

    @Benchmark
    fun publication(): QueryModelSchema = QueryModelSchema(schema.model, schema.capabilities, schema.definition, schema.bindings)

    @Benchmark
    fun publicCursorAdmission(): AdmittedQuery<ICursorQuery> = QueryAdmission.Trusted.cursor(query, schema)

}
