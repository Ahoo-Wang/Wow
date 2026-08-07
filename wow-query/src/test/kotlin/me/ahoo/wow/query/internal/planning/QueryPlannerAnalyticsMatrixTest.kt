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

package me.ahoo.wow.query.internal.planning

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsOverflowPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.analytics.DecodedAnalyticsCursor
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsMetric
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.LogicalFieldType
import me.ahoo.wow.query.internal.schema.Nullability
import me.ahoo.wow.query.internal.schema.Presence
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.QueryFieldSchema
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.function.Consumer

class QueryPlannerAnalyticsMatrixTest {

    private val planner = QueryPlanner()
    private val cursorText = QueryFieldId.Path(listOf("state", "cursorText"))
    private val cursorBoolean = QueryFieldId.Path(listOf("state", "cursorBoolean"))
    private val cursorInt64 = QueryFieldId.Path(listOf("state", "cursorInt64"))
    private val itemAmount = QueryFieldId.Path(listOf("state", "items", "amount"))
    private val schema = QueryDocumentSchema(
        PlanningFixtures.target,
        PlanningFixtures.schema.fields.values + listOf(
            scalarField(cursorText, LogicalFieldType.Text),
            scalarField(cursorBoolean, LogicalFieldType.Boolean),
            scalarField(cursorInt64, LogicalFieldType.Int64),
            scalarField(itemAmount, LogicalFieldType.Decimal),
        ),
        PlanningFixtures.schema.searchScopes.values,
    )

    @Test
    fun `metric matrix should plan every portable numeric and instant aggregation`() {
        val cases = listOf(
            MetricCase(
                AnalyticsMetric.Min(alias("numericMin"), amount()),
                PlannedAnalyticsMetric.Min::class.java,
                true
            ),
            MetricCase(
                AnalyticsMetric.Max(alias("numericMax"), amount()),
                PlannedAnalyticsMetric.Max::class.java,
                true
            ),
            MetricCase(
                AnalyticsMetric.Sum(alias("numericSum"), amount()),
                PlannedAnalyticsMetric.Sum::class.java,
                true
            ),
            MetricCase(
                AnalyticsMetric.Average(alias("numericAverage"), amount()),
                PlannedAnalyticsMetric.Average::class.java,
                true,
            ),
            MetricCase(
                AnalyticsMetric.Min(alias("instantMin"), PlanningFixtures.path("state", "createdAt")),
                PlannedAnalyticsMetric.Min::class.java,
                false,
            ),
            MetricCase(
                AnalyticsMetric.Max(alias("instantMax"), PlanningFixtures.path("state", "createdAt")),
                PlannedAnalyticsMetric.Max::class.java,
                false,
            ),
        )

        cases.forEach { case ->
            val plan = plan(metricQuery(case.metric, case.needsNumericPolicy))
            val metric = plan.metrics.values.single()
            case.plannedType.isInstance(metric).assert().isTrue()
            metricField(metric).assert().isEqualTo(
                if (case.needsNumericPolicy) PlanningFixtures.amount else PlanningFixtures.createdAt,
            )
        }
    }

    @Test
    fun `nested numeric metrics should fail before backend compilation`() {
        val query = metricQuery(
            AnalyticsMetric.Sum(alias("nestedTotal"), PlanningFixtures.path("state", "items", "amount")),
            needsNumericPolicy = true,
        )

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_METRIC_TYPE_UNSUPPORTED,
            "$.input.query.metrics[0].field",
        ) {
            plan(query)
        }
    }

    @Test
    fun `cursor matrix should preserve exact canonical scalar types`() {
        cursorCases().forEach { case ->
            val query = grouped(listOf(case))
            val initial = plan(query)
            val cursor = cursor(initial, listOf(case), listOf(case.value))

            checkNotNull(
                plan(query.copy(bucketWindow = AnalyticsBucketWindow.After(25, cursor)))
                    .bucketWindow.afterKey,
            ).values.assert().containsExactly(case.value)
            assertInvalidCursor(
                query,
                cursor.copy(afterKey = NonEmptyList.of(case.wrongType)),
                "$.input.query.bucketWindow.cursor.afterKey[0]",
            )
        }
    }

    @Test
    fun `compound cursor should bind alias order arity and every key`() {
        val cases = cursorCases()
        val query = grouped(cases)
        val initial = plan(query)
        val values = cases.map(CursorCase::value)
        val cursor = cursor(initial, cases, values)

        checkNotNull(
            plan(query.copy(bucketWindow = AnalyticsBucketWindow.After(25, cursor)))
                .bucketWindow.afterKey,
        ).values.assert().isEqualTo(values)
        assertInvalidCursor(
            query,
            cursor.copy(dimensionAliases = nonEmpty(cases.map(CursorCase::alias).reversed())),
            "$.input.query.bucketWindow.cursor.dimensionAliases",
        )
        assertInvalidCursor(
            query,
            cursor.copy(afterKey = nonEmpty(values.dropLast(1))),
            "$.input.query.bucketWindow.cursor.afterKey",
        )
    }

    private fun cursorCases(): List<CursorCase> = listOf(
        CursorCase(alias("text"), cursorText, NormalizedValue.Text("A"), NormalizedValue.Int64(1)),
        CursorCase(
            alias("boolean"),
            cursorBoolean,
            NormalizedValue.BooleanValue(true),
            NormalizedValue.Text("true"),
        ),
        CursorCase(alias("int64"), cursorInt64, NormalizedValue.Int64(10), NormalizedValue.Decimal(BigDecimal.TEN)),
        CursorCase(
            alias("decimal"),
            PlanningFixtures.amount,
            NormalizedValue.Decimal(BigDecimal.TEN),
            NormalizedValue.Int64(10),
        ),
        CursorCase(
            alias("instant"),
            PlanningFixtures.createdAt,
            NormalizedValue.InstantValue(Instant.parse("2026-08-07T00:00:00Z")),
            NormalizedValue.Text("2026-08-07T00:00:00Z"),
        ),
    )

    private fun grouped(cases: List<CursorCase>): AnalyticsQuery = AnalyticsQuery(
        NormalizedCondition.All,
        AnalyticsGrouping.By(
            nonEmpty(
                cases.map { case -> AnalyticsDimension(case.alias, logical(case.field)) },
            ),
        ),
        NonEmptyList.of(AnalyticsMetric.DocumentCount(alias("count"))),
        bucketWindow = AnalyticsBucketWindow.First(25),
    )

    private fun metricQuery(metric: AnalyticsMetric, needsNumericPolicy: Boolean): AnalyticsQuery = AnalyticsQuery(
        NormalizedCondition.All,
        AnalyticsGrouping.Global,
        NonEmptyList.of(metric),
        numericPolicy = numericPolicy().takeIf { needsNumericPolicy },
    )

    private fun plan(query: AnalyticsQuery): AnalyticsQueryPlan =
        (
            planner.plan(
                NormalizedQueryInvocation(
                    PlanningFixtures.target,
                    QueryOperation.ANALYZE,
                    QueryResultShape.ANALYTICS,
                    NormalizedQueryInput.Analytics(query),
                ),
                schema,
                PlanningConstraints(me.ahoo.wow.query.internal.model.QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as AnalyticsQueryPlan

    private fun cursor(
        plan: AnalyticsQueryPlan,
        cases: List<CursorCase>,
        values: List<NormalizedValue>,
    ): DecodedAnalyticsCursor = DecodedAnalyticsCursor(
        PlanningFixtures.target,
        plan.fingerprint,
        nonEmpty(cases.map(CursorCase::alias)),
        nonEmpty(values),
    )

    private fun assertInvalidCursor(
        query: AnalyticsQuery,
        cursor: DecodedAnalyticsCursor,
        path: String,
    ) {
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_BINDING, path) {
            plan(query.copy(bucketWindow = AnalyticsBucketWindow.After(25, cursor)))
        }
    }

    private fun assertRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        path: String,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }

    private fun scalarField(id: QueryFieldId.Path, type: LogicalFieldType): QueryFieldSchema = QueryFieldSchema(
        id,
        type,
        Presence.OPTIONAL,
        Nullability.NULLABLE,
        emptySet(),
        setOf(FieldCapability.AGGREGATABLE),
    )

    private fun logical(field: QueryFieldId.Path) = PlanningFixtures.path(*field.segments.toTypedArray())

    private fun amount() = PlanningFixtures.path("state", "amount")

    private fun alias(value: String) = AnalyticsAlias(value)

    private fun numericPolicy() = AnalyticsNumericPolicy(
        AnalyticsNumericPromotion.DECIMAL128,
        precision = 34,
        scale = 8,
        roundingMode = RoundingMode.HALF_EVEN,
        overflowPolicy = AnalyticsOverflowPolicy.REJECT,
    )

    private fun metricField(metric: PlannedAnalyticsMetric): QueryFieldId = when (metric) {
        is PlannedAnalyticsMetric.Min -> metric.field
        is PlannedAnalyticsMetric.Max -> metric.field
        is PlannedAnalyticsMetric.Sum -> metric.field
        is PlannedAnalyticsMetric.Average -> metric.field
        is PlannedAnalyticsMetric.DocumentCount -> error("Document count has no field.")
    }

    private fun <T> nonEmpty(values: List<T>): NonEmptyList<T> = checkNotNull(NonEmptyList.from(values))

    private data class MetricCase(
        val metric: AnalyticsMetric,
        val plannedType: Class<out PlannedAnalyticsMetric>,
        val needsNumericPolicy: Boolean,
    )

    private data class CursorCase(
        val alias: AnalyticsAlias,
        val field: QueryFieldId.Path,
        val value: NormalizedValue,
        val wrongType: NormalizedValue,
    )
}
