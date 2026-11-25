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

package me.ahoo.wow.postgres.query.review

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.postgres.query.PostgresSnapshotStorageAdapter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Deep review round 1 of the test kit: where the in-memory query storage, which `memoryQueries` and a node on the
 * memory store read, answers otherwise than PostgreSQL. The schema is the query TCK's order state
 * ([QueryBackendSpec.DECLARATION]), compiled once over the memory storage's facts and once over
 * [PostgresSnapshotStorageAdapter]'s, the facts of the table this module creates. Each case asserts what PostgreSQL
 * does, so it fails on memory until the two agree.
 */
class MemoryQueryReviewR1Test {
    private val type = AggregateType("review", "order")
    private val memory = InMemoryQueryStorage()

    private val memorySchema: QueryModelSchema by lazy { schemaOver(memory.binding(type).storage) }
    private val postgresSchema: QueryModelSchema by lazy { schemaOver(PostgresSnapshotStorageAdapter) }

    @Test
    fun `every capability memory grants a field should be one PostgreSQL grants it`() {
        // Memory grants what the strict family table allows (query/query/.../memory/InMemoryQueryBackend.kt:284-296,
        // `dateOperands` on, no cardinality check). PostgreSQL leaves a date string PRESENCE alone (`dateOperands =
        // false`, PostgresSnapshotStorageAdapter.kt:60, and TEXT provides no DATE family, :167-173), and a
        // multi-valued path no SORT, CURSOR_SORT or AGGREGATE_* (:91-97, :150-165).
        val extra = paths(memorySchema).associateWith { path ->
            capabilities(memorySchema, path) - capabilities(postgresSchema, path)
        }.filterValues { it.isNotEmpty() }
        extra.assert().describedAs("what memory grants and PostgreSQL does not, by field").isEmpty()
    }

    @Test
    fun `a range over a date string should be refused on memory as PostgreSQL refuses it`() {
        // An `Instant` state property is a date string (ManifestSchemaReader.kt:163,303 reads `date-time` as
        // Temporal.Date): a saga's `placedAt > 2026-01-01` is refused at admission on PostgreSQL
        // (PostgresSnapshotStorageAdapter.kt:60) and admitted by memoryQueries, so the spec passes where the node
        // throws.
        val placedAt = QueryField("state.placedAt")
        val query = ListQuery(GreaterThanFilter(placedAt, "2026-01-01T00:00:00Z"))
        val refused = QueryViolation.UnsupportedCapability(placedAt, setOf(QueryCapability.RANGE))
        refusalOf { QueryAdmission.Trusted.list(query, postgresSchema) }.assert().isEqualTo(refused)
        refusalOf { QueryAdmission.Trusted.list(query, memorySchema) }.assert().isEqualTo(refused)
    }

    @Test
    fun `an expression filter should compare the exact decimal product, as PostgreSQL's numeric does`() {
        // PostgreSQL computes an expression in `numeric` and binds the operand as a BigDecimal
        // (PostgresFilterCompiler.kt:134-135, :310-329): 0.1 * 3.0 is 0.30, not greater than 0.3, and 0.2 * 3.0 is
        // 0.60. Memory computes in Double (InMemoryFilters.kt:241-257, InMemoryValues.kt:107-111):
        // 0.1 * 3.0 = 0.30000000000000004 > 0.3.
        runBlocking {
            store(snapshot("tenth", """{"amount":0.1}"""), snapshot("fifth", """{"amount":0.2}"""))
            val tripled = AggregationExpression.Binary(
                AggregationExpressionOperator.MULTIPLY,
                AggregationExpression.Field(QueryField("state.amount")),
                AggregationExpression.Constant(3.0),
            )
            val query = ListQuery(ExpressionFilter(tripled, ComparisonOperator.GT, 0.3))
            memory.binding(type).backend.list(QueryAdmission.Trusted.list(query, memorySchema)).toList()
                .map { it.path("aggregateId").stringValue() }.assert().containsExactly("fifth")
        }
    }

    @Test
    fun `a sum of decimals should be exact, as PostgreSQL's numeric sum is`() {
        // PostgreSQL sums `numeric` (PostgresAggregationCompiler.kt:168, `sum(...::numeric)`) and answers it as a
        // BigDecimal (PostgresQueryBackend.kt:277): 0.1 + 0.2 is 0.3. Memory sums Doubles
        // (InMemoryAggregation.kt:201-213): 0.30000000000000004.
        runBlocking {
            store(snapshot("tenth", """{"amount":0.1}"""), snapshot("fifth", """{"amount":0.2}"""))
            val sum = AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Field(QueryField("state.amount")),
                "total",
            )
            val query = AggregationQuery(metrics = listOf(sum))
            val row = memory.binding(type).backend.aggregate(QueryAdmission.Trusted.aggregate(query, memorySchema))
                .toList().single()
            row.path("total").decimalValue().assert().describedAs(row.toString())
                .isEqualByComparingTo(BigDecimal("0.3"))
        }
    }

    private fun schemaOver(adapter: QueryStorageAdapter): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        val source = BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))
        return runBlocking { DefaultQueryModelSchemaProvider(context, listOf(source), adapter).schema() }
    }

    private fun store(vararg snapshots: SnapshotRecord) {
        snapshots.forEach(memory::save)
    }

    private fun snapshot(id: String, state: String) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = 1,
        schemaFingerprint = "review",
        state = state,
        firstEventTime = 1_700_000_000_000L,
        lastEventTime = 1_700_000_000_000L,
    )

    private companion object {
        /** The logical path of each field the schema binds that has no map key, as a query names it. */
        fun paths(schema: QueryModelSchema): List<String> = schema.bindings.keys
            .filter { path -> path.segments.isNotEmpty() && path.segments.none { it is QueryPathSegment.Key } }
            .map { path -> path.segments.filterIsInstance<QueryPathSegment.Property>().joinToString(".") { it.name } }
            .distinct()

        fun capabilities(schema: QueryModelSchema, path: String): Set<QueryCapability> =
            schema.field(QueryField(path))?.capabilities.orEmpty()

        fun refusalOf(admit: () -> Any): QueryViolation? =
            assertThrows<QuerySchemaValidationException> { admit() }.violation
    }
}
