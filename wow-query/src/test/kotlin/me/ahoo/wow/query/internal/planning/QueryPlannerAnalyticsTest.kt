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
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketOrder
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsCondition
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsMissingPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNullPlacement
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsOverflowPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.analytics.AnalyticsTextCollation
import me.ahoo.wow.query.internal.analytics.DecodedAnalyticsCursor
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.PlanFingerprint
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsBucketOrder
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsCondition
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsMetric
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import java.math.RoundingMode
import java.util.function.Consumer

class QueryPlannerAnalyticsTest {

    private val planner = QueryPlanner()

    @Test
    fun `global document count should produce a portable exact plan`() {
        val plan = plan(
            AnalyticsQuery(
                userCondition = NormalizedCondition.All,
                grouping = AnalyticsGrouping.Global,
                metrics = NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
            ),
        )

        plan.grouping.assert().isEqualTo(PlannedAnalyticsGrouping.Global)
        plan.metrics.values.single().assert().isEqualTo(
            PlannedAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
        )
        plan.bucketOrder.assert().isEqualTo(PlannedAnalyticsBucketOrder.Global)
        plan.bucketWindow.limit.assert().isEqualTo(1)
        plan.having.assert().isEqualTo(PlannedAnalyticsCondition.All)
        plan.filter.user.assert().isEqualTo(me.ahoo.wow.query.internal.plan.PlannedCondition.All)
        plan.requiredConsistency.assert().isEqualTo(AnalyticsConsistency.EVENTUAL)
        plan.requiredCompleteness.assert().isEqualTo(AnalyticsCompleteness.EXACT)
        plan.fingerprint.value.length.assert().isEqualTo(64)

        val withUnusedPolicy = plan(global().copy(numericPolicy = numericPolicy()))
        withUnusedPolicy.numericPolicy.assert().isNull()
        withUnusedPolicy.fingerprint.assert().isEqualTo(plan.fingerprint)
    }

    @Test
    fun `grouped numeric metrics should bind canonical fields and aggregation capability`() {
        val amount = AnalyticsAlias("amount")
        val total = AnalyticsAlias("total")
        val plan = plan(grouped(amount, total))
        val grouping = plan.grouping as PlannedAnalyticsGrouping.By

        grouping.dimensions.values.single().field.assert().isEqualTo(PlanningFixtures.amount)
        grouping.dimensions.values.single().missingPolicy.assert().isEqualTo(AnalyticsMissingPolicy.AS_NULL_BUCKET)
        (plan.metrics.values.last() as PlannedAnalyticsMetric.Sum).field.assert().isEqualTo(PlanningFixtures.amount)
        plan.requiredCapabilities.fieldRequirements.getValue(PlanningFixtures.amount).assert()
            .contains(me.ahoo.wow.query.backend.FieldCapability.AGGREGATABLE)
        plan.bucketOrder.assert().isEqualTo(
            PlannedAnalyticsBucketOrder.DimensionKeyAscending(
                AnalyticsNullPlacement.FIRST,
                AnalyticsTextCollation.BINARY,
            ),
        )
    }

    @Test
    fun `analytics should reject unsupported grain shape and precision gaps`() {
        val eventTarget = QueryTarget(PlanningFixtures.target.namedAggregate, QueryDocumentKind.EVENT_STREAM)
        val eventSchema = QueryDocumentSchema(
            eventTarget,
            PlanningFixtures.schema.fields.values,
            PlanningFixtures.schema.searchScopes.values,
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_DOCUMENT_KIND_UNSUPPORTED,
            "$.target.documentKind",
        ) {
            planner.plan(invocation(global(), eventTarget), eventSchema, constraints())
        }

        val arrayDimension = AnalyticsQuery(
            NormalizedCondition.All,
            AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(
                        AnalyticsAlias("items"),
                        PlanningFixtures.path("state", "items"),
                        AnalyticsMissingPolicy.EXCLUDE,
                    ),
                ),
            ),
            NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_DIMENSION_TYPE_UNSUPPORTED,
            "$.input.query.grouping.dimensions[0].field",
        ) {
            plan(arrayDimension)
        }

        val nestedDimension = arrayDimension.copy(
            grouping = AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(
                        AnalyticsAlias("itemName"),
                        PlanningFixtures.path("state", "items", "name"),
                    ),
                ),
            ),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_DIMENSION_TYPE_UNSUPPORTED,
            "$.input.query.grouping.dimensions[0].field",
        ) {
            plan(nestedDimension)
        }
    }

    @Test
    fun `numeric metrics should require a supported Decimal128 policy`() {
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_NUMERIC_POLICY_REQUIRED,
            "$.input.query.numericPolicy",
        ) {
            plan(grouped(numericPolicy = null))
        }
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_NUMERIC_POLICY_UNSUPPORTED,
            "$.input.query.numericPolicy",
        ) {
            plan(grouped(numericPolicy = numericPolicy().copy(precision = 35)))
        }
    }

    @Test
    fun `analytics should support snapshot consistency and reject unsupported having order and completeness`() {
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_HAVING_UNSUPPORTED,
            "$.input.query.having",
        ) {
            plan(grouped().copy(having = AnalyticsCondition.Predicate(AnalyticsAlias("count"))))
        }

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_ORDER_UNSUPPORTED,
            "$.input.query.bucketOrder",
        ) {
            plan(grouped().copy(bucketOrder = AnalyticsBucketOrder.MetricDescending(AnalyticsAlias("total"))))
        }

        plan(grouped().copy(requiredConsistency = AnalyticsConsistency.SNAPSHOT))
            .requiredConsistency.assert().isEqualTo(AnalyticsConsistency.SNAPSHOT)

        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_COMPLETENESS_UNSUPPORTED,
            "$.input.query.requiredCompleteness",
        ) {
            plan(grouped().copy(requiredCompleteness = AnalyticsCompleteness.APPROXIMATE))
        }
    }

    @Test
    fun `analytics aliases should be unique before field planning`() {
        val duplicate = AnalyticsAlias("amount")
        val query = AnalyticsQuery(
            NormalizedCondition.All,
            AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(
                        duplicate,
                        PlanningFixtures.path("state", "amount"),
                        AnalyticsMissingPolicy.EXCLUDE,
                    ),
                ),
            ),
            NonEmptyList.of(AnalyticsMetric.Sum(duplicate, PlanningFixtures.path("state", "missing"))),
            numericPolicy = numericPolicy(),
        )

        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.DUPLICATE_ANALYTICS_ALIAS,
            "$.input.query.metrics[0].alias",
        ) {
            plan(query)
        }
    }

    @Test
    fun `analytics should allow instant min max but reject instant sum and non portable filters`() {
        val instantMin = AnalyticsQuery(
            NormalizedCondition.All,
            AnalyticsGrouping.Global,
            NonEmptyList.of(
                AnalyticsMetric.Min(
                    AnalyticsAlias("firstCreatedAt"),
                    PlanningFixtures.path("state", "createdAt"),
                ),
            ),
        )
        (plan(instantMin).metrics.values.single() as PlannedAnalyticsMetric.Min).field.assert()
            .isEqualTo(PlanningFixtures.createdAt)

        val instantSum = instantMin.copy(
            metrics = NonEmptyList.of(
                AnalyticsMetric.Sum(
                    AnalyticsAlias("sumCreatedAt"),
                    PlanningFixtures.path("state", "createdAt"),
                ),
            ),
            numericPolicy = numericPolicy(),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.ANALYTICS_METRIC_TYPE_UNSUPPORTED,
            "$.input.query.metrics[0].field",
        ) {
            plan(instantSum)
        }

        val native = instantMin.copy(
            userCondition = NormalizedCondition.Native(BackendId("mongo"), Utf8Json("{}")),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            "$.input.query.userCondition",
        ) {
            val compatible = PlanningConstraints(QueryValidationMode.COMPATIBLE)
            plan(native, compatible)
        }

        val mandatorySearch = PlanningConstraints(
            QueryValidationMode.STRICT,
            mandatoryCondition = NormalizedCondition.Search(
                PlanningFixtures.legacySearch(PlanningFixtures.path("state", "description")),
                "secure",
            ),
        )
        assertRejected(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            "$.constraints.mandatoryCondition",
        ) {
            plan(global(), mandatorySearch)
        }
    }

    @Test
    fun `decoded cursor should bind target fingerprint order arity and value type`() {
        val first = plan(grouped())
        val cursor = DecodedAnalyticsCursor(
            target = PlanningFixtures.target,
            planFingerprint = first.fingerprint,
            dimensionAliases = NonEmptyList.of(AnalyticsAlias("amount")),
            afterKey = NonEmptyList.of(NormalizedValue.Decimal(java.math.BigDecimal("10.00"))),
        )
        val resumed = plan(
            grouped().copy(bucketWindow = AnalyticsBucketWindow.After(limit = 25, cursor = cursor)),
        )

        resumed.fingerprint.assert().isEqualTo(first.fingerprint)

        val wrong = cursor.copy(planFingerprint = PlanFingerprint("f".repeat(64)))
        assertRejected(
            QueryRejectionCategory.INVALID_CURSOR,
            QueryRejectionCode.INVALID_CURSOR_BINDING,
            "$.input.query.bucketWindow.cursor.planFingerprint",
        ) {
            plan(grouped().copy(bucketWindow = AnalyticsBucketWindow.After(limit = 25, cursor = wrong)))
        }

        val globalCursor = cursor.copy(planFingerprint = plan(global()).fingerprint)
        assertRejected(
            QueryRejectionCategory.INVALID_CURSOR,
            QueryRejectionCode.INVALID_CURSOR_BINDING,
            "$.input.query.bucketWindow.cursor",
        ) {
            plan(global().copy(bucketWindow = AnalyticsBucketWindow.After(limit = 1, cursor = globalCursor)))
        }
    }

    @Test
    fun `decoded cursor should reject target order arity and type mismatches`() {
        val query = grouped()
        val first = plan(query)
        val cursor = DecodedAnalyticsCursor(
            PlanningFixtures.target,
            first.fingerprint,
            NonEmptyList.of(AnalyticsAlias("amount")),
            NonEmptyList.of(NormalizedValue.Decimal(java.math.BigDecimal.TEN)),
        )
        val eventTarget = QueryTarget(PlanningFixtures.target.namedAggregate, QueryDocumentKind.EVENT_STREAM)

        assertInvalidCursor(query, cursor.copy(target = eventTarget), "$.input.query.bucketWindow.cursor.target")
        assertInvalidCursor(
            query,
            cursor.copy(dimensionAliases = NonEmptyList.of(AnalyticsAlias("other"))),
            "$.input.query.bucketWindow.cursor.dimensionAliases",
        )
        assertInvalidCursor(
            query,
            cursor.copy(
                afterKey = NonEmptyList.of(
                    NormalizedValue.Decimal(java.math.BigDecimal.ONE),
                    NormalizedValue.Decimal(java.math.BigDecimal.TEN),
                ),
            ),
            "$.input.query.bucketWindow.cursor.afterKey",
        )
        assertInvalidCursor(
            query,
            cursor.copy(afterKey = NonEmptyList.of(NormalizedValue.Text("ten"))),
            "$.input.query.bucketWindow.cursor.afterKey[0]",
        )
        assertInvalidCursor(
            query,
            cursor.copy(afterKey = NonEmptyList.of(NormalizedValue.Int64(10))),
            "$.input.query.bucketWindow.cursor.afterKey[0]",
        )
    }

    @Test
    fun `cursor should represent a missing non-null dimension as null only for null buckets`() {
        val schema = schemaWithAmount(Presence.OPTIONAL, Nullability.NON_NULL)
        val query = grouped()
        val first = plan(query, schema = schema)
        val cursor = DecodedAnalyticsCursor(
            PlanningFixtures.target,
            first.fingerprint,
            NonEmptyList.of(AnalyticsAlias("amount")),
            NonEmptyList.of(NormalizedValue.Null),
        )

        plan(
            query.copy(bucketWindow = AnalyticsBucketWindow.After(25, cursor)),
            schema = schema,
        ).bucketWindow.afterKey.assert().isNotNull()
        val excludedQuery = query.copy(
            grouping = AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(
                        AnalyticsAlias("amount"),
                        PlanningFixtures.path("state", "amount"),
                        AnalyticsMissingPolicy.EXCLUDE,
                    ),
                ),
            ),
        )
        val excludedCursor = cursor.copy(planFingerprint = plan(excludedQuery).fingerprint)
        assertInvalidCursor(excludedQuery, excludedCursor, "$.input.query.bucketWindow.cursor.afterKey[0]")

        val requiredSchema = schemaWithAmount(Presence.REQUIRED, Nullability.NON_NULL)
        val requiredCursor = cursor.copy(planFingerprint = plan(query, schema = requiredSchema).fingerprint)
        assertInvalidCursor(
            query,
            requiredCursor,
            "$.input.query.bucketWindow.cursor.afterKey[0]",
            requiredSchema,
        )
    }

    @Test
    fun `analytics limits should be enforced without entering semantic fingerprint`() {
        val query = grouped()
        val unrestricted = plan(query)
        val exactLimits = AnalyticsPlanningConstraint.Limits(
            maxDimensions = 1,
            maxMetrics = 2,
            maxBucketLimit = 25,
        )
        val limited = plan(query, constraints(exactLimits))

        limited.fingerprint.assert().isEqualTo(unrestricted.fingerprint)
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.ANALYTICS_METRIC_LIMIT_EXCEEDED,
            "$.input.query.metrics",
        ) {
            plan(query, constraints(exactLimits.copy(maxMetrics = 1)))
        }
        val twoDimensions = query.copy(
            grouping = AnalyticsGrouping.By(
                NonEmptyList.of(
                    AnalyticsDimension(
                        AnalyticsAlias("amount"),
                        PlanningFixtures.path("state", "amount"),
                    ),
                    AnalyticsDimension(
                        AnalyticsAlias("name"),
                        PlanningFixtures.path("state", "name"),
                    ),
                ),
            ),
        )
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.ANALYTICS_DIMENSION_LIMIT_EXCEEDED,
            "$.input.query.grouping.dimensions",
        ) {
            plan(twoDimensions, constraints(exactLimits))
        }
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.ANALYTICS_BUCKET_LIMIT_EXCEEDED,
            "$.input.query.bucketWindow.limit",
        ) {
            plan(query, constraints(exactLimits.copy(maxBucketLimit = 24)))
        }

        val globalWithIrrelevantLimit = global().copy(bucketWindow = AnalyticsBucketWindow.First(10_000))
        plan(
            globalWithIrrelevantLimit,
            constraints(AnalyticsPlanningConstraint.Limits(1, 1, 1)),
        ).bucketWindow.limit.assert().isEqualTo(1)
    }

    private fun global(): AnalyticsQuery = AnalyticsQuery(
        NormalizedCondition.All,
        AnalyticsGrouping.Global,
        NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
    )

    private fun grouped(
        dimensionAlias: AnalyticsAlias = AnalyticsAlias("amount"),
        metricAlias: AnalyticsAlias = AnalyticsAlias("total"),
        numericPolicy: AnalyticsNumericPolicy? = numericPolicy(),
    ): AnalyticsQuery = AnalyticsQuery(
        userCondition = NormalizedCondition.All,
        grouping = AnalyticsGrouping.By(
            NonEmptyList.of(
                AnalyticsDimension(
                    dimensionAlias,
                    PlanningFixtures.path("state", "amount"),
                    AnalyticsMissingPolicy.AS_NULL_BUCKET,
                ),
            ),
        ),
        metrics = NonEmptyList.of(
            AnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
            AnalyticsMetric.Sum(metricAlias, PlanningFixtures.path("state", "amount")),
        ),
        bucketWindow = AnalyticsBucketWindow.First(limit = 25),
        numericPolicy = numericPolicy,
    )

    private fun numericPolicy(): AnalyticsNumericPolicy = AnalyticsNumericPolicy(
        promotion = AnalyticsNumericPromotion.DECIMAL128,
        precision = 34,
        scale = 8,
        roundingMode = RoundingMode.HALF_EVEN,
        overflowPolicy = AnalyticsOverflowPolicy.REJECT,
    )

    private fun plan(
        query: AnalyticsQuery,
        constraints: PlanningConstraints = constraints(),
        schema: QueryDocumentSchema = PlanningFixtures.schema,
    ): AnalyticsQueryPlan =
        (planner.plan(invocation(query), schema, constraints) as PlanningDecision.Planned).plan
            as AnalyticsQueryPlan

    private fun invocation(
        query: AnalyticsQuery,
        target: QueryTarget = PlanningFixtures.target,
    ): NormalizedQueryInvocation = NormalizedQueryInvocation(
        target,
        QueryOperation.ANALYZE,
        QueryResultShape.ANALYTICS,
        NormalizedQueryInput.Analytics(query),
    )

    private fun constraints(
        analytics: AnalyticsPlanningConstraint = AnalyticsPlanningConstraint.Unrestricted,
    ): PlanningConstraints = PlanningConstraints(QueryValidationMode.STRICT, analyticsConstraint = analytics)

    private fun assertInvalidCursor(
        query: AnalyticsQuery,
        cursor: DecodedAnalyticsCursor,
        path: String,
        schema: QueryDocumentSchema = PlanningFixtures.schema,
    ) {
        assertRejected(QueryRejectionCategory.INVALID_CURSOR, QueryRejectionCode.INVALID_CURSOR_BINDING, path) {
            plan(
                query.copy(bucketWindow = AnalyticsBucketWindow.After(limit = 25, cursor = cursor)),
                schema = schema,
            )
        }
    }

    private fun schemaWithAmount(
        presence: Presence,
        nullability: Nullability,
    ): QueryDocumentSchema {
        val amount = PlanningFixtures.schema.fields.getValue(PlanningFixtures.amount).let { field ->
            QueryFieldSchema(
                field.id,
                field.type,
                presence,
                nullability,
                field.allowedOperators,
                field.capabilities,
                field.logicalAliases,
            )
        }
        return QueryDocumentSchema(
            PlanningFixtures.target,
            PlanningFixtures.schema.fields.values.map { field ->
                if (field.id == PlanningFixtures.amount) amount else field
            },
            PlanningFixtures.schema.searchScopes.values,
        )
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
}
