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
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketOrder
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsMissingPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsOverflowPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import java.math.RoundingMode

class QueryPlannerAnalyticsFingerprintTest {
    private val planner = QueryPlanner()

    @Test
    fun `analytics fingerprint should encode semantics and canonicalize equivalent input`() {
        val query = grouped()
        val base = plan(query)
        plan(query.copy(bucketOrder = AnalyticsBucketOrder.DimensionKeyAscending)).fingerprint.assert()
            .isEqualTo(base.fingerprint)

        val excludeMissing = query.copy(
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
        val average = query.copy(
            metrics = NonEmptyList.of(
                AnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
                AnalyticsMetric.Average(AnalyticsAlias("total"), PlanningFixtures.path("state", "amount")),
            ),
        )
        listOf(
            excludeMissing,
            grouped(metricAlias = AnalyticsAlias("renamed")),
            average,
            query.copy(bucketWindow = AnalyticsBucketWindow.First(24)),
            query.copy(numericPolicy = numericPolicy().copy(precision = 33)),
            query.copy(numericPolicy = numericPolicy().copy(scale = 7)),
            query.copy(numericPolicy = numericPolicy().copy(roundingMode = RoundingMode.DOWN)),
        ).forEach { variant ->
            plan(variant).fingerprint.assert().isNotEqualTo(base.fingerprint)
        }

        base.fingerprint.value.assert().isEqualTo(
            "97fb24dc0ee83efcc07e4bbbc1bcb897f40a1b3709383af9644c894438ece3a7",
        )
        plan(global().copy(bucketWindow = AnalyticsBucketWindow.First(1))).fingerprint.assert().isEqualTo(
            plan(global().copy(bucketWindow = AnalyticsBucketWindow.First(10_000))).fingerprint,
        )
    }

    private fun plan(query: AnalyticsQuery): AnalyticsQueryPlan {
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.ANALYZE,
            QueryResultShape.ANALYTICS,
            NormalizedQueryInput.Analytics(query),
        )
        return (
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as AnalyticsQueryPlan
    }

    private fun global(): AnalyticsQuery = AnalyticsQuery(
        NormalizedCondition.All,
        AnalyticsGrouping.Global,
        NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
    )

    private fun grouped(metricAlias: AnalyticsAlias = AnalyticsAlias("total")): AnalyticsQuery = AnalyticsQuery(
        userCondition = NormalizedCondition.All,
        grouping = AnalyticsGrouping.By(
            NonEmptyList.of(
                AnalyticsDimension(
                    AnalyticsAlias("amount"),
                    PlanningFixtures.path("state", "amount"),
                    AnalyticsMissingPolicy.AS_NULL_BUCKET,
                ),
            ),
        ),
        metrics = NonEmptyList.of(
            AnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
            AnalyticsMetric.Sum(metricAlias, PlanningFixtures.path("state", "amount")),
        ),
        bucketWindow = AnalyticsBucketWindow.First(25),
        numericPolicy = numericPolicy(),
    )

    private fun numericPolicy(): AnalyticsNumericPolicy = AnalyticsNumericPolicy(
        AnalyticsNumericPromotion.DECIMAL128,
        precision = 34,
        scale = 8,
        roundingMode = RoundingMode.HALF_EVEN,
        overflowPolicy = AnalyticsOverflowPolicy.REJECT,
    )
}
