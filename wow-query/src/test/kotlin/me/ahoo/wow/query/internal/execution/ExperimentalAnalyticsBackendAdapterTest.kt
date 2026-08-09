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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.query.internal.execution

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.BackendAnalyticsBucket
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsPage
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
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
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import me.ahoo.wow.query.backend.AnalyticsQueryBackend as BackendAnalyticsQueryBackend
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias as InternalAnalyticsAlias

class ExperimentalAnalyticsBackendAdapterTest {
    @Test
    fun `should translate analytics plan options and result without leaking internal types`() {
        val capturedPlan = AtomicReference<BackendAnalyticsQueryPlan>()
        val capturedOptions = AtomicReference<QueryBackendExecutionOptions>()
        val backend = BackendAnalyticsQueryBackend { plan, options ->
            capturedPlan.set(plan)
            capturedOptions.set(options)
            Mono.just(
                BackendAnalyticsPage(
                    listOf(
                        BackendAnalyticsBucket(
                            mapOf(AnalyticsAlias("amount") to NormalizedValue.Decimal("10.00".toBigDecimal())),
                            mapOf(
                                AnalyticsAlias("count") to NormalizedValue.Int64(2),
                                AnalyticsAlias("total") to NormalizedValue.Decimal("20.00".toBigDecimal()),
                            ),
                        ),
                    ),
                    listOf(NormalizedValue.Decimal("10.00".toBigDecimal())),
                    BackendAnalyticsConsistency.EVENTUAL,
                    BackendAnalyticsCompleteness.EXACT,
                ),
            )
        }
        val deadline = Instant.parse("2026-08-08T00:00:00Z")
        val options = QueryExecutionOptions(
            deadline,
            QueryExecutionBudget(
                maxScannedRecords = 100,
                maxReturnedRecords = 20,
                maxPageWindow = 50,
                maxCandidateBuckets = 30,
                maxReturnedBuckets = 10,
                maxCursorPages = 3,
                allowDiskUse = true,
            ),
        )

        val result = ExperimentalAnalyticsBackendAdapter(
            backend,
            PlanningFixtures.schema
        ).analyze(plan(), options).block()!!

        val translated = capturedPlan.get()
        translated.target.assert().isEqualTo(PlanningFixtures.target)
        (translated.grouping as BackendAnalyticsGrouping.By).dimensions.single().alias.value.assert()
            .isEqualTo("amount")
        translated.metrics.filterIsInstance<BackendAnalyticsMetric.Sum>().single().field.assert()
            .isEqualTo(PlanningFixtures.amount)
        capturedOptions.get().assert().isEqualTo(
            QueryBackendExecutionOptions(deadline, 20, 100, 50, 30, 10, 3, true),
        )
        result.buckets.single().keys.keys.single().value.assert().isEqualTo("amount")
        result.buckets.single().metrics.keys.map(InternalAnalyticsAlias::value).assert()
            .containsExactly("count", "total")
        result.afterKey!!.assert().containsExactly(NormalizedValue.Decimal("10".toBigDecimal()))
    }

    @Test
    fun `should reject malformed backend values as mapping failure before they leave the adapter`() {
        val backend = BackendAnalyticsQueryBackend { _, _ ->
            Mono.just(
                BackendAnalyticsPage(
                    listOf(
                        BackendAnalyticsBucket(
                            mapOf(AnalyticsAlias("amount") to NormalizedValue.Bytes(byteArrayOf(1))),
                            mapOf(AnalyticsAlias("count") to NormalizedValue.Int64(-1)),
                        ),
                    ),
                    listOf(NormalizedValue.Bytes(byteArrayOf(1))),
                    BackendAnalyticsConsistency.EVENTUAL,
                    BackendAnalyticsCompleteness.EXACT,
                ),
            )
        }

        assertThrownBy<QueryBackendException> {
            ExperimentalAnalyticsBackendAdapter(backend, PlanningFixtures.schema)
                .analyze(plan(), QueryExecutionOptions(null, QueryExecutionBudget()))
                .block()
        }.satisfies(
            java.util.function.Consumer { error ->
                error.kind.assert().isEqualTo(QueryBackendFailureKind.MAPPING_FAILURE)
            },
        )
    }

    private fun plan(): AnalyticsQueryPlan {
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.ANALYZE,
            QueryResultShape.ANALYTICS,
            NormalizedQueryInput.Analytics(
                AnalyticsQuery(
                    NormalizedCondition.All,
                    AnalyticsGrouping.By(
                        NonEmptyList.of(
                            AnalyticsDimension(
                                InternalAnalyticsAlias("amount"),
                                PlanningFixtures.path("state", "amount"),
                                AnalyticsMissingPolicy.AS_NULL_BUCKET,
                            ),
                        ),
                    ),
                    NonEmptyList.of(
                        AnalyticsMetric.DocumentCount(InternalAnalyticsAlias("count")),
                        AnalyticsMetric.Sum(
                            InternalAnalyticsAlias("total"),
                            PlanningFixtures.path("state", "amount"),
                        ),
                    ),
                    numericPolicy = AnalyticsNumericPolicy(
                        AnalyticsNumericPromotion.DECIMAL128,
                        34,
                        2,
                        RoundingMode.HALF_UP,
                        AnalyticsOverflowPolicy.REJECT,
                    ),
                ),
            ),
        )
        return (
            QueryPlanner().plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as AnalyticsQueryPlan
    }
}
