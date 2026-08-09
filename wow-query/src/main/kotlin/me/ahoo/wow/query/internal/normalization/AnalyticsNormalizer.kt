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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.wow.api.query.analytics.AnalyticsGroupingKind
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind
import me.ahoo.wow.query.internal.admission.AdmittedAnalyticsQuery
import me.ahoo.wow.query.internal.admission.AdmittedCondition
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
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPromotion
import me.ahoo.wow.query.internal.analytics.AnalyticsOverflowPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.cursor.QueryCursorToken
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.value.NonEmptyList

internal object AnalyticsNormalizer {
    fun normalize(
        query: AdmittedAnalyticsQuery,
        path: QueryRejectionPath,
        conditionNormalizer: (AdmittedCondition, QueryRejectionPath) -> NormalizedCondition,
        fieldNormalizer: (String) -> LogicalField.Path,
    ): AnalyticsQuery = AnalyticsQuery(
        userCondition = conditionNormalizer(query.condition, path.property("condition")),
        grouping = normalizeGrouping(query, fieldNormalizer),
        metrics = normalizeMetrics(query, fieldNormalizer),
        having = AnalyticsCondition.All,
        bucketOrder = AnalyticsBucketOrder.Default,
        bucketWindow = AnalyticsBucketWindow.First(query.window.limit),
        numericPolicy = normalizeNumericPolicy(query),
        requiredConsistency = when (query.consistency) {
            me.ahoo.wow.api.query.analytics.AnalyticsConsistency.EVENTUAL -> AnalyticsConsistency.EVENTUAL
            me.ahoo.wow.api.query.analytics.AnalyticsConsistency.SNAPSHOT -> AnalyticsConsistency.SNAPSHOT
        },
        requiredCompleteness = when (query.completeness) {
            me.ahoo.wow.api.query.analytics.AnalyticsCompleteness.EXACT -> AnalyticsCompleteness.EXACT
        },
        cursorToken = query.window.cursor?.value?.let(::QueryCursorToken),
    )

    private fun normalizeGrouping(
        query: AdmittedAnalyticsQuery,
        fieldNormalizer: (String) -> LogicalField.Path,
    ): AnalyticsGrouping = when (query.grouping.kind) {
        AnalyticsGroupingKind.GLOBAL -> AnalyticsGrouping.Global
        AnalyticsGroupingKind.BY -> AnalyticsGrouping.By(
            checkNotNull(
                NonEmptyList.from(
                    query.grouping.dimensions.map { dimension ->
                        AnalyticsDimension(
                            AnalyticsAlias(dimension.alias),
                            fieldNormalizer(dimension.field),
                            when (dimension.missingPolicy) {
                                me.ahoo.wow.api.query.analytics.AnalyticsMissingPolicy.EXCLUDE ->
                                    AnalyticsMissingPolicy.EXCLUDE

                                me.ahoo.wow.api.query.analytics.AnalyticsMissingPolicy.AS_NULL_BUCKET ->
                                    AnalyticsMissingPolicy.AS_NULL_BUCKET
                            },
                        )
                    },
                ),
            ),
        )
    }

    private fun normalizeMetrics(
        query: AdmittedAnalyticsQuery,
        fieldNormalizer: (String) -> LogicalField.Path,
    ): NonEmptyList<AnalyticsMetric> = checkNotNull(
        NonEmptyList.from(
            query.metrics.map { metric ->
                val alias = AnalyticsAlias(metric.alias)
                when (metric.kind) {
                    AnalyticsMetricKind.DOCUMENT_COUNT -> AnalyticsMetric.DocumentCount(alias)
                    AnalyticsMetricKind.MIN -> AnalyticsMetric.Min(alias, fieldNormalizer(checkNotNull(metric.field)))
                    AnalyticsMetricKind.MAX -> AnalyticsMetric.Max(alias, fieldNormalizer(checkNotNull(metric.field)))
                    AnalyticsMetricKind.SUM -> AnalyticsMetric.Sum(alias, fieldNormalizer(checkNotNull(metric.field)))
                    AnalyticsMetricKind.AVERAGE ->
                        AnalyticsMetric.Average(alias, fieldNormalizer(checkNotNull(metric.field)))
                }
            },
        ),
    )

    private fun normalizeNumericPolicy(query: AdmittedAnalyticsQuery): AnalyticsNumericPolicy? =
        query.numericPolicy?.let { numericPolicy ->
            AnalyticsNumericPolicy(
                promotion = when (numericPolicy.promotion) {
                    me.ahoo.wow.api.query.analytics.AnalyticsNumericPromotion.DECIMAL128 ->
                        AnalyticsNumericPromotion.DECIMAL128
                },
                precision = numericPolicy.precision,
                scale = numericPolicy.scale,
                roundingMode = numericPolicy.roundingMode,
                overflowPolicy = when (numericPolicy.overflowPolicy) {
                    me.ahoo.wow.api.query.analytics.AnalyticsOverflowPolicy.REJECT -> AnalyticsOverflowPolicy.REJECT
                },
            )
        }
}
