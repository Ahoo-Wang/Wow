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

package me.ahoo.wow.query.internal.execution

import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.policy.QueryExecutionContext
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

internal data class QueryExecutionOptions(
    val deadline: Instant?,
    val budget: QueryExecutionBudget,
) {
    companion object {
        fun from(context: QueryExecutionContext): QueryExecutionOptions =
            QueryExecutionOptions(context.deadline, context.budget)
    }
}

internal data class BackendRecord(
    val identity: String,
    val document: NormalizedValue.ObjectValue,
    val completeness: BackendRecordCompleteness,
) {
    init {
        require(identity.isNotBlank()) {
            "Backend record identity must not be blank."
        }
    }
}

internal enum class BackendRecordCompleteness {
    COMPLETE,
    UNKNOWN,
}

internal enum class BackendTotalRelation {
    EXACT,
    LOWER_BOUND,
    UNKNOWN,
}

internal enum class BackendPageConsistency {
    SAME_INPUT,
    INDEPENDENT,
    UNKNOWN,
}

internal class BackendPage(
    records: Iterable<BackendRecord>,
    val total: Long,
    val totalRelation: BackendTotalRelation,
    val consistency: BackendPageConsistency,
) {
    val records: List<BackendRecord> = Collections.unmodifiableList(records.toList())

    init {
        require(total >= 0) {
            "Backend page total must not be negative."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BackendPage &&
            records == other.records &&
            total == other.total &&
            totalRelation == other.totalRelation &&
            consistency == other.consistency

    override fun hashCode(): Int {
        var result = records.hashCode()
        result = 31 * result + total.hashCode()
        result = 31 * result + totalRelation.hashCode()
        result = 31 * result + consistency.hashCode()
        return result
    }
}

internal class BackendAnalyticsBucket(
    keys: Map<AnalyticsAlias, NormalizedValue>,
    metrics: Map<AnalyticsAlias, NormalizedValue>,
) {
    val keys: Map<AnalyticsAlias, NormalizedValue> = immutableValues(keys)
    val metrics: Map<AnalyticsAlias, NormalizedValue> = immutableValues(metrics)

    override fun equals(other: Any?): Boolean =
        this === other || other is BackendAnalyticsBucket && keys == other.keys && metrics == other.metrics

    override fun hashCode(): Int = 31 * keys.hashCode() + metrics.hashCode()

    private fun immutableValues(values: Map<AnalyticsAlias, NormalizedValue>): Map<AnalyticsAlias, NormalizedValue> {
        val copy = LinkedHashMap<AnalyticsAlias, NormalizedValue>(values.size)
        values.entries.sortedBy { entry -> entry.key.value }.forEach { entry -> copy[entry.key] = entry.value }
        return Collections.unmodifiableMap(copy)
    }
}

internal class BackendAnalyticsPage(
    buckets: Iterable<BackendAnalyticsBucket>,
    afterKey: List<NormalizedValue>?,
    val consistency: AnalyticsConsistency,
    val completeness: AnalyticsCompleteness,
) {
    val buckets: List<BackendAnalyticsBucket> = Collections.unmodifiableList(buckets.toList())
    val afterKey: List<NormalizedValue>? = afterKey?.let { Collections.unmodifiableList(it.toList()) }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BackendAnalyticsPage &&
            buckets == other.buckets &&
            afterKey == other.afterKey &&
            consistency == other.consistency &&
            completeness == other.completeness

    override fun hashCode(): Int {
        var result = buckets.hashCode()
        result = 31 * result + (afterKey?.hashCode() ?: 0)
        result = 31 * result + consistency.hashCode()
        result = 31 * result + completeness.hashCode()
        return result
    }
}

internal interface RecordQueryBackend {
    fun single(plan: SingleQueryPlan, options: QueryExecutionOptions): Mono<BackendRecord>

    fun stream(plan: StreamQueryPlan, options: QueryExecutionOptions): Flux<BackendRecord>

    fun page(plan: PageQueryPlan, options: QueryExecutionOptions): Mono<BackendPage>

    fun count(plan: CountQueryPlan, options: QueryExecutionOptions): Mono<Long>
}

internal fun interface AnalyticsQueryBackend {
    fun analyze(plan: AnalyticsQueryPlan, options: QueryExecutionOptions): Mono<BackendAnalyticsPage>
}

internal class QueryBackendException(
    val kind: QueryBackendFailureKind,
    cause: Throwable? = null,
) : IllegalStateException("Query backend failure: $kind", cause)

internal enum class QueryBackendFailureKind {
    UNAVAILABLE,
    TIMEOUT,
    INCOMPLETE_RESULT,
    MAPPING_FAILURE,
}
