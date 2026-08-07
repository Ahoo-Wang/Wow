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

package me.ahoo.wow.query.internal.analytics

import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.plan.PlanFingerprint
import me.ahoo.wow.query.internal.value.NonEmptyList
import java.math.RoundingMode

@JvmInline
internal value class AnalyticsAlias(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Analytics alias must not be blank."
        }
    }
}

internal enum class AnalyticsMissingPolicy {
    EXCLUDE,
    AS_NULL_BUCKET,
}

internal data class AnalyticsDimension(
    val alias: AnalyticsAlias,
    val field: LogicalField,
    val missingPolicy: AnalyticsMissingPolicy = AnalyticsMissingPolicy.EXCLUDE,
)

internal sealed interface AnalyticsGrouping {
    data object Global : AnalyticsGrouping

    data class By(val dimensions: NonEmptyList<AnalyticsDimension>) : AnalyticsGrouping
}

internal sealed interface AnalyticsMetric {
    val alias: AnalyticsAlias

    data class DocumentCount(
        override val alias: AnalyticsAlias,
    ) : AnalyticsMetric

    data class Min(
        override val alias: AnalyticsAlias,
        val field: LogicalField,
    ) : AnalyticsMetric

    data class Max(
        override val alias: AnalyticsAlias,
        val field: LogicalField,
    ) : AnalyticsMetric

    data class Sum(
        override val alias: AnalyticsAlias,
        val field: LogicalField,
    ) : AnalyticsMetric

    data class Average(
        override val alias: AnalyticsAlias,
        val field: LogicalField,
    ) : AnalyticsMetric
}

internal sealed interface AnalyticsCondition {
    data object All : AnalyticsCondition

    /** Reserved logical alias reference. The first portable planner rejects it deterministically. */
    data class Predicate(val alias: AnalyticsAlias) : AnalyticsCondition
}

internal sealed interface AnalyticsBucketOrder {
    data object Default : AnalyticsBucketOrder

    data object DimensionKeyAscending : AnalyticsBucketOrder

    data class MetricDescending(val alias: AnalyticsAlias) : AnalyticsBucketOrder
}

internal sealed interface AnalyticsBucketWindow {
    val limit: Int

    data class First(override val limit: Int) : AnalyticsBucketWindow {
        init {
            require(limit > 0) {
                "Analytics bucket limit must be positive."
            }
        }
    }

    data class After(
        override val limit: Int,
        val cursor: DecodedAnalyticsCursor,
    ) : AnalyticsBucketWindow {
        init {
            require(limit > 0) {
                "Analytics bucket limit must be positive."
            }
        }
    }
}

internal enum class AnalyticsConsistency {
    EVENTUAL,
    SNAPSHOT,
}

internal enum class AnalyticsCompleteness {
    EXACT,
    APPROXIMATE,
}

internal enum class AnalyticsOverflowPolicy {
    REJECT,
}

internal enum class AnalyticsNumericPromotion {
    DECIMAL128,
}

internal enum class AnalyticsNullPlacement {
    FIRST,
}

internal enum class AnalyticsTextCollation {
    BINARY,
}

internal data class AnalyticsNumericPolicy(
    val promotion: AnalyticsNumericPromotion,
    val precision: Int,
    val scale: Int,
    val roundingMode: RoundingMode,
    val overflowPolicy: AnalyticsOverflowPolicy,
) {
    init {
        require(precision > 0) {
            "Analytics numeric precision must be positive."
        }
        require(scale in 0..precision) {
            "Analytics numeric scale must be between zero and precision."
        }
    }
}

/**
 * Decoded semantic cursor state. Token encoding, signing and expiry are deliberately outside Phase 1.
 */
internal data class DecodedAnalyticsCursor(
    val target: QueryTarget,
    val planFingerprint: PlanFingerprint,
    val dimensionAliases: NonEmptyList<AnalyticsAlias>,
    val afterKey: NonEmptyList<NormalizedValue>,
)

/**
 * Backend-independent analytics input after its future wire adapter has normalized field and value semantics.
 */
internal data class AnalyticsQuery(
    val userCondition: NormalizedCondition,
    val grouping: AnalyticsGrouping,
    val metrics: NonEmptyList<AnalyticsMetric>,
    val having: AnalyticsCondition = AnalyticsCondition.All,
    val bucketOrder: AnalyticsBucketOrder = AnalyticsBucketOrder.Default,
    val bucketWindow: AnalyticsBucketWindow = AnalyticsBucketWindow.First(DEFAULT_BUCKET_LIMIT),
    val numericPolicy: AnalyticsNumericPolicy? = null,
    val requiredConsistency: AnalyticsConsistency = AnalyticsConsistency.EVENTUAL,
    val requiredCompleteness: AnalyticsCompleteness = AnalyticsCompleteness.EXACT,
) {
    private companion object {
        const val DEFAULT_BUCKET_LIMIT = 100
    }
}
