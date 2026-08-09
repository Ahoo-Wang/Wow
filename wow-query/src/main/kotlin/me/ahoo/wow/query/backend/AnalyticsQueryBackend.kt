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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.backend

import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import reactor.core.publisher.Mono
import java.math.RoundingMode
import java.util.Collections
import java.util.LinkedHashMap

@ExperimentalQueryBackendApi
@JvmInline
value class AnalyticsAlias(val value: String) {
    init {
        require(value.isNotBlank()) { "Analytics alias must not be blank." }
        require(value.length <= MAX_ALIAS_LENGTH) { "Analytics alias must not exceed $MAX_ALIAS_LENGTH characters." }
        require(value.none(Char::isISOControl)) { "Analytics alias must not contain control characters." }
        require('.' !in value && '$' !in value) { "Analytics alias must be a safe backend field name." }
    }

    private companion object {
        const val MAX_ALIAS_LENGTH = 128
    }
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsMissingPolicy {
    EXCLUDE,
    AS_NULL_BUCKET,
}

@ExperimentalQueryBackendApi
data class BackendAnalyticsDimension(
    val alias: AnalyticsAlias,
    val field: QueryFieldId,
    val missingPolicy: BackendAnalyticsMissingPolicy,
)

@ExperimentalQueryBackendApi
sealed interface BackendAnalyticsGrouping {
    data object Global : BackendAnalyticsGrouping

    class By(dimensions: Iterable<BackendAnalyticsDimension>) : BackendAnalyticsGrouping {
        val dimensions: List<BackendAnalyticsDimension> = immutableNonEmpty(dimensions, "Analytics dimensions")

        override fun equals(other: Any?): Boolean = this === other || other is By && dimensions == other.dimensions

        override fun hashCode(): Int = dimensions.hashCode()
    }
}

@ExperimentalQueryBackendApi
sealed interface BackendAnalyticsMetric {
    val alias: AnalyticsAlias

    data class DocumentCount(override val alias: AnalyticsAlias) : BackendAnalyticsMetric

    data class Min(override val alias: AnalyticsAlias, val field: QueryFieldId) : BackendAnalyticsMetric

    data class Max(override val alias: AnalyticsAlias, val field: QueryFieldId) : BackendAnalyticsMetric

    data class Sum(override val alias: AnalyticsAlias, val field: QueryFieldId) : BackendAnalyticsMetric

    data class Average(override val alias: AnalyticsAlias, val field: QueryFieldId) : BackendAnalyticsMetric
}

@ExperimentalQueryBackendApi
sealed interface BackendAnalyticsCondition {
    data object All : BackendAnalyticsCondition
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsNullPlacement {
    FIRST,
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsTextCollation {
    BINARY,
}

@ExperimentalQueryBackendApi
sealed interface BackendAnalyticsBucketOrder {
    data object Global : BackendAnalyticsBucketOrder

    data class DimensionKeyAscending(
        val nullPlacement: BackendAnalyticsNullPlacement,
        val textCollation: BackendAnalyticsTextCollation,
    ) : BackendAnalyticsBucketOrder
}

@ExperimentalQueryBackendApi
class BackendAnalyticsPageWindow(
    val limit: Int,
    afterKey: Iterable<NormalizedValue>? = null,
) {
    val afterKey: List<NormalizedValue>? = afterKey?.let { Collections.unmodifiableList(it.toList()) }

    init {
        require(limit > 0) { "Analytics bucket limit must be positive." }
        require(this.afterKey == null || this.afterKey.isNotEmpty()) { "Analytics after-key must not be empty." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is BackendAnalyticsPageWindow && limit == other.limit && afterKey == other.afterKey

    override fun hashCode(): Int = 31 * limit + (afterKey?.hashCode() ?: 0)
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsConsistency {
    EVENTUAL,
    SNAPSHOT,
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsCompleteness {
    EXACT,
    APPROXIMATE,
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsOverflowPolicy {
    REJECT,
}

@ExperimentalQueryBackendApi
enum class BackendAnalyticsNumericPromotion {
    DECIMAL128,
}

@ExperimentalQueryBackendApi
data class BackendAnalyticsNumericPolicy(
    val promotion: BackendAnalyticsNumericPromotion,
    val precision: Int,
    val scale: Int,
    val roundingMode: RoundingMode,
    val overflowPolicy: BackendAnalyticsOverflowPolicy,
) {
    init {
        require(precision in 1..34) { "Analytics Decimal128 precision must be between 1 and 34." }
        require(scale in 0..precision) { "Analytics numeric scale must be between zero and precision." }
    }
}

@ExperimentalQueryBackendApi
class BackendAnalyticsQueryPlan(
    val target: QueryTarget,
    val schemaContractId: SchemaContractId,
    val filter: BackendEnforcedFilter,
    val grouping: BackendAnalyticsGrouping,
    metrics: Iterable<BackendAnalyticsMetric>,
    val having: BackendAnalyticsCondition,
    val bucketOrder: BackendAnalyticsBucketOrder,
    val bucketWindow: BackendAnalyticsPageWindow,
    val numericPolicy: BackendAnalyticsNumericPolicy?,
    val requiredConsistency: BackendAnalyticsConsistency,
    val requiredCompleteness: BackendAnalyticsCompleteness,
    val requiredCapabilities: BackendRequiredCapabilities,
    val semanticTier: SemanticTier,
    val fingerprint: PlanFingerprint,
) {
    val operation: QueryOperation = QueryOperation.ANALYZE
    val metrics: List<BackendAnalyticsMetric> = immutableNonEmpty(metrics, "Analytics metrics")

    init {
        val aliases = grouping.aliases() + this.metrics.map(BackendAnalyticsMetric::alias)
        require(aliases.distinct().size == aliases.size) { "Analytics aliases must be unique." }
        when (val currentGrouping = grouping) {
            BackendAnalyticsGrouping.Global -> require(
                bucketOrder == BackendAnalyticsBucketOrder.Global &&
                    bucketWindow.afterKey == null &&
                    bucketWindow.limit == 1,
            ) {
                "Global analytics must use limit one and global ordering without a cursor."
            }

            is BackendAnalyticsGrouping.By -> {
                require(bucketOrder is BackendAnalyticsBucketOrder.DimensionKeyAscending) {
                    "Grouped analytics must use dimension-key ordering."
                }
                require(
                    bucketWindow.afterKey == null || bucketWindow.afterKey.size == currentGrouping.dimensions.size,
                ) {
                    "Analytics after-key arity must match the grouping dimensions."
                }
            }
        }
    }
}

@ExperimentalQueryBackendApi
class BackendAnalyticsBucket(
    keys: Map<AnalyticsAlias, NormalizedValue>,
    metrics: Map<AnalyticsAlias, NormalizedValue>,
) {
    val keys: Map<AnalyticsAlias, NormalizedValue> = immutableAnalyticsValues(keys)
    val metrics: Map<AnalyticsAlias, NormalizedValue> = immutableAnalyticsValues(metrics)

    override fun equals(other: Any?): Boolean =
        this === other || other is BackendAnalyticsBucket && keys == other.keys && metrics == other.metrics

    override fun hashCode(): Int = 31 * keys.hashCode() + metrics.hashCode()
}

@ExperimentalQueryBackendApi
class BackendAnalyticsPage @JvmOverloads constructor(
    buckets: Iterable<BackendAnalyticsBucket>,
    afterKey: Iterable<NormalizedValue>?,
    val consistency: BackendAnalyticsConsistency,
    val completeness: BackendAnalyticsCompleteness,
    val cursorState: BackendAnalyticsCursorState? = null,
) {
    val buckets: List<BackendAnalyticsBucket> = Collections.unmodifiableList(buckets.toList())
    val afterKey: List<NormalizedValue>? = afterKey?.let { Collections.unmodifiableList(it.toList()) }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BackendAnalyticsPage &&
            buckets == other.buckets &&
            afterKey == other.afterKey &&
            consistency == other.consistency &&
            completeness == other.completeness &&
            cursorState == other.cursorState

    override fun hashCode(): Int {
        var result = buckets.hashCode()
        result = 31 * result + (afterKey?.hashCode() ?: 0)
        result = 31 * result + consistency.hashCode()
        result = 31 * result + completeness.hashCode()
        result = 31 * result + (cursorState?.hashCode() ?: 0)
        return result
    }
}

/** Opaque, backend-owned continuation state. It is persisted server-side and never embedded in a public cursor. */
@ExperimentalQueryBackendApi
class BackendAnalyticsCursorState(payload: ByteArray) {
    private val frozenPayload = payload.copyOf()

    init {
        require(frozenPayload.isNotEmpty()) { "Analytics cursor state must not be empty." }
    }

    fun payload(): ByteArray = frozenPayload.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is BackendAnalyticsCursorState && frozenPayload.contentEquals(other.frozenPayload)

    override fun hashCode(): Int = frozenPayload.contentHashCode()
}

@ExperimentalQueryBackendApi
fun interface AnalyticsQueryBackend {
    fun analyze(plan: BackendAnalyticsQueryPlan, options: QueryBackendExecutionOptions): Mono<BackendAnalyticsPage>

    /**
     * Continues a backend-owned snapshot when [cursorState] is present. Stateless backends reject instead of silently
     * ignoring physical continuation state.
     */
    fun analyze(
        plan: BackendAnalyticsQueryPlan,
        options: QueryBackendExecutionOptions,
        cursorState: BackendAnalyticsCursorState?,
    ): Mono<BackendAnalyticsPage> = if (cursorState == null) {
        analyze(plan, options)
    } else {
        Mono.error(QueryBackendException(QueryBackendFailureKind.UNSUPPORTED))
    }
}

/** Closes backend-owned analytics cursor state transferred to the Gateway. Close must be idempotent. */
@ExperimentalQueryBackendApi
fun interface AnalyticsQueryCursorLifecycle {
    fun close(cursorState: BackendAnalyticsCursorState): Mono<Void>
}

private fun BackendAnalyticsGrouping.aliases(): List<AnalyticsAlias> =
    when (this) {
        BackendAnalyticsGrouping.Global -> emptyList()
        is BackendAnalyticsGrouping.By -> dimensions.map(BackendAnalyticsDimension::alias)
    }

private fun <T> immutableNonEmpty(values: Iterable<T>, name: String): List<T> =
    Collections.unmodifiableList(values.toList()).also { result ->
        require(result.isNotEmpty()) { "$name must not be empty." }
    }

private fun immutableAnalyticsValues(
    values: Map<AnalyticsAlias, NormalizedValue>,
): Map<AnalyticsAlias, NormalizedValue> {
    val copy = LinkedHashMap<AnalyticsAlias, NormalizedValue>(values.size)
    values.entries.sortedBy { entry -> entry.key.value }.forEach { entry -> copy[entry.key] = entry.value }
    return Collections.unmodifiableMap(copy)
}
