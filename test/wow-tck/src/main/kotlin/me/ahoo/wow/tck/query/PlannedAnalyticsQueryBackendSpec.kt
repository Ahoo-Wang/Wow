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

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap
import java.util.function.Consumer

sealed interface ExactNumericAnalyticsExpectation {
    class Supported(metrics: Map<AnalyticsAlias, NormalizedValue>) : ExactNumericAnalyticsExpectation {
        val metrics: Map<AnalyticsAlias, NormalizedValue> = Collections.unmodifiableMap(LinkedHashMap(metrics))
    }

    data object Unsupported : ExactNumericAnalyticsExpectation
}

/** Shared exact-count contract for portable MongoDB and Elasticsearch analytics backends. */
interface PlannedAnalyticsQueryBackendSpec {
    val analyticsBackend: AnalyticsQueryBackend

    val analyticsOptions: QueryBackendExecutionOptions

    val expectedGlobalCount: Long

    val expectedUnrestrictedGlobalCount: Long

    val expectedFirstKey: NormalizedValue

    val expectedSecondKey: NormalizedValue

    val expectedNullBucketCount: Long

    val dimensionAlias: AnalyticsAlias

    val countAlias: AnalyticsAlias

    val exactNumericAnalyticsExpectation: ExactNumericAnalyticsExpectation

    fun globalCountPlan(): BackendAnalyticsQueryPlan

    fun unrestrictedGlobalCountPlan(): BackendAnalyticsQueryPlan

    fun groupedCountPlan(afterKey: List<NormalizedValue>?, limit: Int = 1): BackendAnalyticsQueryPlan

    fun nullBucketCountPlan(): BackendAnalyticsQueryPlan

    fun exactNumericMetricPlan(): BackendAnalyticsQueryPlan

    @Test
    fun `portable analytics global count should be exact and eventual`() {
        val page = analyticsBackend.analyze(globalCountPlan(), analyticsOptions).block()!!

        page.buckets.assert().hasSize(1)
        page.buckets.single().keys.assert().isEmpty()
        page.buckets.single().metrics.assert()
            .containsEntry(countAlias, NormalizedValue.Int64(expectedGlobalCount))
        page.afterKey.assert().isNull()
        page.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        page.completeness.assert().isEqualTo(BackendAnalyticsCompleteness.EXACT)
    }

    @Test
    fun `portable analytics mandatory filter should exclude unauthorized and deleted documents`() {
        val restricted = analyticsBackend.analyze(globalCountPlan(), analyticsOptions).block()!!
        val unrestricted = analyticsBackend.analyze(unrestrictedGlobalCountPlan(), analyticsOptions).block()!!

        restricted.buckets.single().metrics.assert()
            .containsEntry(countAlias, NormalizedValue.Int64(expectedGlobalCount))
        unrestricted.buckets.single().metrics.assert()
            .containsEntry(countAlias, NormalizedValue.Int64(expectedUnrestrictedGlobalCount))
    }

    @Test
    fun `portable analytics grouped cursor should replay in stable key order`() {
        val first = analyticsBackend.analyze(groupedCountPlan(null), analyticsOptions).block()!!

        first.buckets.assert().hasSize(1)
        first.buckets.single().keys.assert().containsEntry(dimensionAlias, expectedFirstKey)
        first.afterKey.assert().isNotNull()

        val second = analyticsBackend.analyze(groupedCountPlan(first.afterKey), analyticsOptions).block()!!
        second.buckets.assert().hasSize(1)
        second.buckets.single().keys.assert().containsEntry(dimensionAlias, expectedSecondKey)
        second.afterKey?.let { afterKey ->
            val terminal = analyticsBackend.analyze(groupedCountPlan(afterKey), analyticsOptions).block()!!
            terminal.buckets.assert().isEmpty()
            terminal.afterKey.assert().isNull()
        }
    }

    @Test
    fun `portable analytics should coalesce missing and explicit null into one bucket`() {
        val page = analyticsBackend.analyze(nullBucketCountPlan(), analyticsOptions).block()!!

        page.buckets.assert().hasSize(1)
        page.buckets.single().keys.values.assert().containsExactly(NormalizedValue.Null)
        page.buckets.single().metrics.assert()
            .containsEntry(countAlias, NormalizedValue.Int64(expectedNullBucketCount))
    }

    @Test
    fun `exact numeric analytics capability should execute precisely or reject explicitly`() {
        when (val expectation = exactNumericAnalyticsExpectation) {
            is ExactNumericAnalyticsExpectation.Supported -> {
                val page = analyticsBackend.analyze(exactNumericMetricPlan(), analyticsOptions).block()!!
                page.buckets.assert().hasSize(1)
                expectation.metrics.forEach { (alias, value) ->
                    page.buckets.single().metrics.assert().containsEntry(alias, value)
                }
            }

            ExactNumericAnalyticsExpectation.Unsupported -> {
                assertThrownBy<QueryBackendException> {
                    analyticsBackend.analyze(exactNumericMetricPlan(), analyticsOptions).block()
                }.satisfies(
                    Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
                )
            }
        }
    }

    @Test
    fun `portable analytics budgets and expired deadlines should fail closed`() {
        assertBackendFailure(QueryBackendFailureKind.BUDGET_EXCEEDED) {
            analyticsBackend.analyze(
                groupedCountPlan(null, limit = 2),
                analyticsOptions.copy(maxReturnedBuckets = 1),
            ).block()
        }
        assertBackendFailure(QueryBackendFailureKind.UNSUPPORTED) {
            analyticsBackend.analyze(
                globalCountPlan(),
                analyticsOptions.copy(maxScannedRecords = 1),
            ).block()
        }
        assertBackendFailure(QueryBackendFailureKind.TIMEOUT) {
            analyticsBackend.analyze(
                globalCountPlan(),
                analyticsOptions.copy(deadline = Instant.EPOCH),
            ).block()
        }
    }

    private fun assertBackendFailure(kind: QueryBackendFailureKind, action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(kind) },
        )
    }
}
