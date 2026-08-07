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

package me.ahoo.wow.query.internal.plan

import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.analytics.AnalyticsMissingPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsNullPlacement
import me.ahoo.wow.query.internal.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsTextCollation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.RecordResultShape
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.NormalizedPredicateOptions
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.SchemaContractId
import me.ahoo.wow.query.internal.value.NonEmptyList
import java.util.Collections
import java.util.LinkedHashMap

@JvmInline
internal value class PlanFingerprint(val value: String) {
    init {
        require(value.matches(HEX_PATTERN)) {
            "Plan fingerprint must be a SHA-256 hex string."
        }
    }

    private companion object {
        val HEX_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal enum class SemanticTier {
    PORTABLE,
    SEARCH,
    NATIVE,
}

internal sealed interface PlannedCondition {
    data object All : PlannedCondition

    data object None : PlannedCondition

    data class Junction(
        val operator: JunctionOperator,
        val children: NonEmptyList<PlannedCondition>,
    ) : PlannedCondition

    data class Predicate(
        val field: QueryFieldId,
        val operator: PredicateOperator,
        val value: NormalizedValue? = null,
        val options: NormalizedPredicateOptions = NormalizedPredicateOptions(),
    ) : PlannedCondition {
        init {
            require(operator.requiresValue == (value != null)) {
                "Predicate value does not match operator $operator."
            }
        }
    }

    data class ElementMatch(
        val field: QueryFieldId.Path,
        val condition: PlannedCondition,
    ) : PlannedCondition

    data class Search(
        val scope: SearchScopeId,
        val text: String,
    ) : PlannedCondition

    data class Native(
        val backendId: BackendId,
        val payload: Utf8Json,
    ) : PlannedCondition
}

internal data class EnforcedFilter(
    val user: PlannedCondition,
    val mandatory: PlannedCondition,
) {
    val condition: PlannedCondition = PlannedCondition.Junction(
        JunctionOperator.AND,
        NonEmptyList.of(user, mandatory),
    )
}

internal class RequiredCapabilities(
    fieldRequirements: Map<QueryFieldId, Set<FieldCapability>> = emptyMap(),
    searchRequirements: Set<SearchScopeId> = emptySet(),
    val nativeBackend: BackendId? = null,
) {
    val fieldRequirements: Map<QueryFieldId, Set<FieldCapability>> = immutableRequirements(fieldRequirements)
    val searchRequirements: Set<SearchScopeId> = Collections.unmodifiableSet(
        LinkedHashSet(searchRequirements.sortedBy(SearchScopeId::value)),
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RequiredCapabilities &&
            fieldRequirements == other.fieldRequirements &&
            searchRequirements == other.searchRequirements &&
            nativeBackend == other.nativeBackend

    override fun hashCode(): Int {
        var result = fieldRequirements.hashCode()
        result = 31 * result + searchRequirements.hashCode()
        result = 31 * result + (nativeBackend?.hashCode() ?: 0)
        return result
    }

    private fun immutableRequirements(
        requirements: Map<QueryFieldId, Set<FieldCapability>>,
    ): Map<QueryFieldId, Set<FieldCapability>> {
        val copy = LinkedHashMap<QueryFieldId, Set<FieldCapability>>(requirements.size)
        requirements.entries.sortedBy { it.key.stableKey() }.forEach { (field, capabilities) ->
            copy[field] = Collections.unmodifiableSet(LinkedHashSet(capabilities.sortedBy(FieldCapability::name)))
        }
        return Collections.unmodifiableMap(copy)
    }
}

internal sealed interface PlannedProjection {
    data object All : PlannedProjection

    data class Include(val fields: NonEmptyList<QueryFieldId>) : PlannedProjection

    data class Exclude(val fields: NonEmptyList<QueryFieldId>) : PlannedProjection
}

internal enum class PlannedSortOrigin {
    USER,
    STABILITY_TIE_BREAKER,
}

internal data class PlannedSort(
    val field: QueryFieldId,
    val direction: NormalizedSortDirection,
    val origin: PlannedSortOrigin,
)

internal sealed interface QueryPlan {
    val target: QueryTarget
    val operation: QueryOperation
    val schemaContractId: SchemaContractId
    val filter: EnforcedFilter
    val requiredCapabilities: RequiredCapabilities
    val semanticTier: SemanticTier
    val fingerprint: PlanFingerprint
}

internal sealed interface RecordQueryPlan : QueryPlan {
    val resultShape: RecordResultShape
    val projection: PlannedProjection
    val sort: List<PlannedSort>
}

@ConsistentCopyVisibility
internal data class SingleQueryPlan private constructor(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: EnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: PlannedProjection,
    override val sort: List<PlannedSort>,
    override val requiredCapabilities: RequiredCapabilities,
    override val semanticTier: SemanticTier,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.SINGLE
    override val fingerprint: PlanFingerprint by lazy { QueryPlanFingerprint.compute(this) }

    companion object {
        fun create(
            target: QueryTarget,
            schemaContractId: SchemaContractId,
            filter: EnforcedFilter,
            resultShape: RecordResultShape,
            projection: PlannedProjection,
            sort: Iterable<PlannedSort>,
            requiredCapabilities: RequiredCapabilities,
            semanticTier: SemanticTier,
        ): SingleQueryPlan = SingleQueryPlan(
            target,
            schemaContractId,
            filter,
            resultShape,
            projection,
            Collections.unmodifiableList(sort.toList()),
            requiredCapabilities,
            semanticTier,
        )
    }
}

internal sealed interface StreamLimit {
    data object Unbounded : StreamLimit

    data class Bounded(val value: Int) : StreamLimit {
        init {
            require(value > 0) {
                "Bounded stream limit must be positive."
            }
        }
    }
}

@ConsistentCopyVisibility
internal data class StreamQueryPlan private constructor(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: EnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: PlannedProjection,
    override val sort: List<PlannedSort>,
    val limit: StreamLimit,
    override val requiredCapabilities: RequiredCapabilities,
    override val semanticTier: SemanticTier,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.STREAM
    override val fingerprint: PlanFingerprint by lazy { QueryPlanFingerprint.compute(this) }

    companion object {
        fun create(
            target: QueryTarget,
            schemaContractId: SchemaContractId,
            filter: EnforcedFilter,
            resultShape: RecordResultShape,
            projection: PlannedProjection,
            sort: Iterable<PlannedSort>,
            limit: StreamLimit,
            requiredCapabilities: RequiredCapabilities,
            semanticTier: SemanticTier,
        ): StreamQueryPlan = StreamQueryPlan(
            target,
            schemaContractId,
            filter,
            resultShape,
            projection,
            Collections.unmodifiableList(sort.toList()),
            limit,
            requiredCapabilities,
            semanticTier,
        )
    }
}

internal data class PageWindow(
    val offset: Long,
    val size: Int,
) {
    init {
        require(offset >= 0) {
            "Page offset must not be negative."
        }
        require(size > 0) {
            "Page size must be positive."
        }
    }
}

internal enum class TotalMode {
    EXACT,
}

internal enum class RequiredConsistency {
    SAME_INPUT,
}

@ConsistentCopyVisibility
internal data class PageQueryPlan private constructor(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: EnforcedFilter,
    override val resultShape: RecordResultShape,
    override val projection: PlannedProjection,
    override val sort: List<PlannedSort>,
    val page: PageWindow,
    val totalMode: TotalMode,
    val requiredConsistency: RequiredConsistency,
    override val requiredCapabilities: RequiredCapabilities,
    override val semanticTier: SemanticTier,
) : RecordQueryPlan {
    override val operation: QueryOperation = QueryOperation.PAGE
    override val fingerprint: PlanFingerprint by lazy { QueryPlanFingerprint.compute(this) }

    companion object {
        fun create(
            target: QueryTarget,
            schemaContractId: SchemaContractId,
            filter: EnforcedFilter,
            resultShape: RecordResultShape,
            projection: PlannedProjection,
            sort: Iterable<PlannedSort>,
            page: PageWindow,
            totalMode: TotalMode,
            requiredConsistency: RequiredConsistency,
            requiredCapabilities: RequiredCapabilities,
            semanticTier: SemanticTier,
        ): PageQueryPlan = PageQueryPlan(
            target,
            schemaContractId,
            filter,
            resultShape,
            projection,
            Collections.unmodifiableList(sort.toList()),
            page,
            totalMode,
            requiredConsistency,
            requiredCapabilities,
            semanticTier,
        )
    }
}

@ConsistentCopyVisibility
internal data class CountQueryPlan private constructor(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: EnforcedFilter,
    override val requiredCapabilities: RequiredCapabilities,
    override val semanticTier: SemanticTier,
) : QueryPlan {
    override val operation: QueryOperation = QueryOperation.COUNT
    override val fingerprint: PlanFingerprint by lazy { QueryPlanFingerprint.compute(this) }

    companion object {
        fun create(
            target: QueryTarget,
            schemaContractId: SchemaContractId,
            filter: EnforcedFilter,
            requiredCapabilities: RequiredCapabilities,
            semanticTier: SemanticTier,
        ): CountQueryPlan = CountQueryPlan(
            target,
            schemaContractId,
            filter,
            requiredCapabilities,
            semanticTier,
        )
    }
}

internal data class PlannedAnalyticsDimension(
    val alias: AnalyticsAlias,
    val field: QueryFieldId,
    val missingPolicy: AnalyticsMissingPolicy,
)

internal sealed interface PlannedAnalyticsGrouping {
    data object Global : PlannedAnalyticsGrouping

    data class By(val dimensions: NonEmptyList<PlannedAnalyticsDimension>) : PlannedAnalyticsGrouping
}

internal sealed interface PlannedAnalyticsMetric {
    val alias: AnalyticsAlias

    data class DocumentCount(override val alias: AnalyticsAlias) : PlannedAnalyticsMetric

    data class Min(
        override val alias: AnalyticsAlias,
        val field: QueryFieldId,
    ) : PlannedAnalyticsMetric

    data class Max(
        override val alias: AnalyticsAlias,
        val field: QueryFieldId,
    ) : PlannedAnalyticsMetric

    data class Sum(
        override val alias: AnalyticsAlias,
        val field: QueryFieldId,
    ) : PlannedAnalyticsMetric

    data class Average(
        override val alias: AnalyticsAlias,
        val field: QueryFieldId,
    ) : PlannedAnalyticsMetric
}

internal sealed interface PlannedAnalyticsCondition {
    data object All : PlannedAnalyticsCondition
}

internal data class AnalyticsPageWindow(
    val limit: Int,
    val afterKey: NonEmptyList<NormalizedValue>? = null,
) {
    init {
        require(limit > 0) {
            "Analytics bucket limit must be positive."
        }
    }
}

internal sealed interface PlannedAnalyticsBucketOrder {
    data object Global : PlannedAnalyticsBucketOrder

    data class DimensionKeyAscending(
        val nullPlacement: AnalyticsNullPlacement,
        val textCollation: AnalyticsTextCollation,
    ) : PlannedAnalyticsBucketOrder
}

@ConsistentCopyVisibility
internal data class AnalyticsQueryPlan private constructor(
    override val target: QueryTarget,
    override val schemaContractId: SchemaContractId,
    override val filter: EnforcedFilter,
    val grouping: PlannedAnalyticsGrouping,
    val metrics: NonEmptyList<PlannedAnalyticsMetric>,
    val having: PlannedAnalyticsCondition,
    val bucketOrder: PlannedAnalyticsBucketOrder,
    val bucketWindow: AnalyticsPageWindow,
    val numericPolicy: AnalyticsNumericPolicy?,
    val requiredConsistency: AnalyticsConsistency,
    val requiredCompleteness: AnalyticsCompleteness,
    override val requiredCapabilities: RequiredCapabilities,
    override val semanticTier: SemanticTier,
) : QueryPlan {
    override val operation: QueryOperation = QueryOperation.ANALYZE
    override val fingerprint: PlanFingerprint by lazy { QueryPlanFingerprint.compute(this) }

    companion object {
        fun create(
            target: QueryTarget,
            schemaContractId: SchemaContractId,
            filter: EnforcedFilter,
            grouping: PlannedAnalyticsGrouping,
            metrics: NonEmptyList<PlannedAnalyticsMetric>,
            having: PlannedAnalyticsCondition,
            bucketOrder: PlannedAnalyticsBucketOrder,
            bucketWindow: AnalyticsPageWindow,
            numericPolicy: AnalyticsNumericPolicy?,
            requiredConsistency: AnalyticsConsistency,
            requiredCompleteness: AnalyticsCompleteness,
            requiredCapabilities: RequiredCapabilities,
            semanticTier: SemanticTier,
        ): AnalyticsQueryPlan = AnalyticsQueryPlan(
            target,
            schemaContractId,
            filter,
            grouping,
            metrics,
            having,
            bucketOrder,
            bucketWindow,
            numericPolicy,
            requiredConsistency,
            requiredCompleteness,
            requiredCapabilities,
            semanticTier,
        )
    }
}

private fun QueryFieldId.stableKey(): String =
    when (this) {
        is QueryFieldId.System -> "0:${kind.name}"
        is QueryFieldId.Path -> "1:${segments.joinToString("\u0000")}"
    }
