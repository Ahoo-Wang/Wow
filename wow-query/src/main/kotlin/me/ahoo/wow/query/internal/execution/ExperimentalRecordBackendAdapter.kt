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

import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.EnforcedFilter
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.PlannedProjection
import me.ahoo.wow.query.internal.plan.PlannedSort
import me.ahoo.wow.query.internal.plan.PlannedSortOrigin
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.RequiredConsistency
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamLimit
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.plan.TotalMode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import me.ahoo.wow.query.backend.BackendPage as ExperimentalBackendPage
import me.ahoo.wow.query.backend.BackendRecord as ExperimentalBackendRecord
import me.ahoo.wow.query.backend.BackendRecordCompleteness as ExperimentalRecordCompleteness
import me.ahoo.wow.query.backend.RecordQueryBackend as ExperimentalRecordQueryBackend

internal class ExperimentalRecordBackendAdapter(
    private val delegate: ExperimentalRecordQueryBackend,
) : RecordQueryBackend {
    override fun single(plan: SingleQueryPlan, options: QueryExecutionOptions): Mono<BackendRecord> =
        delegate.single(plan.toBackendPlan(), options.toBackendOptions()).map(ExperimentalBackendRecord::toInternal)

    override fun stream(plan: StreamQueryPlan, options: QueryExecutionOptions): Flux<BackendRecord> {
        val backendPlan = plan.toBackendPlan()
        return delegate.stream(backendPlan, options.toBackendOptions()).map(ExperimentalBackendRecord::toInternal)
    }

    override fun page(
        plan: me.ahoo.wow.query.internal.plan.PageQueryPlan,
        options: QueryExecutionOptions,
    ): Mono<BackendPage> = delegate.page(plan.toBackendPlan(), options.toBackendOptions())
        .map(ExperimentalBackendPage::toInternal)

    override fun count(plan: CountQueryPlan, options: QueryExecutionOptions): Mono<Long> =
        delegate.count(plan.toBackendPlan(), options.toBackendOptions())
}

private fun SingleQueryPlan.toBackendPlan(): BackendSingleQueryPlan = BackendSingleQueryPlan(
    target,
    schemaContractId,
    filter.toBackendFilter(),
    resultShape,
    projection.toBackendProjection(),
    sort.map(PlannedSort::toBackendSort),
    requiredCapabilities.toBackendCapabilities(),
    semanticTier.toBackendTier(),
    PlanFingerprint(fingerprint.value),
)

private fun StreamQueryPlan.toBackendPlan(): BackendStreamQueryPlan = BackendStreamQueryPlan(
    target,
    schemaContractId,
    filter.toBackendFilter(),
    resultShape,
    projection.toBackendProjection(),
    sort.map(PlannedSort::toBackendSort),
    when (val streamLimit = limit) {
        is StreamLimit.Bounded -> streamLimit.value
        StreamLimit.Unbounded -> throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)
    },
    requiredCapabilities.toBackendCapabilities(),
    semanticTier.toBackendTier(),
    PlanFingerprint(fingerprint.value),
)

private fun CountQueryPlan.toBackendPlan(): BackendCountQueryPlan = BackendCountQueryPlan(
    target,
    schemaContractId,
    filter.toBackendFilter(),
    requiredCapabilities.toBackendCapabilities(),
    semanticTier.toBackendTier(),
    PlanFingerprint(fingerprint.value),
)

private fun me.ahoo.wow.query.internal.plan.PageQueryPlan.toBackendPlan(): BackendPageQueryPlan =
    BackendPageQueryPlan(
        target,
        schemaContractId,
        filter.toBackendFilter(),
        resultShape,
        projection.toBackendProjection(),
        sort.map(PlannedSort::toBackendSort),
        BackendPageWindow(page.offset, page.size),
        when (totalMode) {
            TotalMode.EXACT -> BackendTotalMode.EXACT
        },
        when (requiredConsistency) {
            RequiredConsistency.SAME_INPUT -> BackendRequiredConsistency.SAME_INPUT
        },
        requiredCapabilities.toBackendCapabilities(),
        semanticTier.toBackendTier(),
        PlanFingerprint(fingerprint.value),
    )

internal fun EnforcedFilter.toBackendFilter(): BackendEnforcedFilter = BackendEnforcedFilter(
    user.toBackendCondition(),
    mandatory.toBackendCondition(),
)

internal fun PlannedCondition.toBackendCondition(): BackendPlannedCondition =
    when (this) {
        PlannedCondition.All -> BackendPlannedCondition.All
        PlannedCondition.None -> BackendPlannedCondition.None
        is PlannedCondition.Junction -> BackendPlannedCondition.Junction(
            operator,
            children.values.map(PlannedCondition::toBackendCondition),
        )

        is PlannedCondition.Predicate -> BackendPlannedCondition.Predicate(field, operator, value, options)
        is PlannedCondition.ElementMatch -> BackendPlannedCondition.ElementMatch(field, condition.toBackendCondition())
        is PlannedCondition.Search -> BackendPlannedCondition.Search(scope, text)
        is PlannedCondition.Native -> BackendPlannedCondition.Native(backendId, payload)
    }

private fun PlannedProjection.toBackendProjection(): BackendProjection =
    when (this) {
        PlannedProjection.All -> BackendProjection.All
        is PlannedProjection.Include -> BackendProjection.Include(fields.values)
        is PlannedProjection.Exclude -> BackendProjection.Exclude(fields.values)
    }

private fun PlannedSort.toBackendSort(): BackendSort = BackendSort(
    field,
    direction,
    when (origin) {
        PlannedSortOrigin.USER -> BackendSortOrigin.USER
        PlannedSortOrigin.STABILITY_TIE_BREAKER -> BackendSortOrigin.STABILITY_TIE_BREAKER
    },
)

internal fun RequiredCapabilities.toBackendCapabilities(): BackendRequiredCapabilities = BackendRequiredCapabilities(
    fieldRequirements,
    searchRequirements,
    nativeBackend,
)

internal fun me.ahoo.wow.query.internal.plan.SemanticTier.toBackendTier(): me.ahoo.wow.query.backend.SemanticTier =
    me.ahoo.wow.query.backend.SemanticTier.valueOf(name)

internal fun QueryExecutionOptions.toBackendOptions(): QueryBackendExecutionOptions =
    QueryBackendExecutionOptions(
        deadline = deadline,
        maxReturnedRecords = budget.maxReturnedRecords,
        maxScannedRecords = budget.maxScannedRecords,
        maxPageWindow = budget.maxPageWindow,
        maxCandidateBuckets = budget.maxCandidateBuckets,
        maxReturnedBuckets = budget.maxReturnedBuckets,
        maxCursorPages = budget.maxCursorPages,
        allowDiskUse = budget.allowDiskUse,
    )

private fun ExperimentalBackendRecord.toInternal(): BackendRecord = BackendRecord(
    identity,
    document,
    when (completeness) {
        ExperimentalRecordCompleteness.COMPLETE -> BackendRecordCompleteness.COMPLETE
        ExperimentalRecordCompleteness.UNKNOWN -> BackendRecordCompleteness.UNKNOWN
    },
)

private fun ExperimentalBackendPage.toInternal(): BackendPage = BackendPage(
    records.map(ExperimentalBackendRecord::toInternal),
    total,
    when (totalRelation) {
        BackendTotalRelation.EXACT -> me.ahoo.wow.query.internal.execution.BackendTotalRelation.EXACT
        BackendTotalRelation.LOWER_BOUND -> me.ahoo.wow.query.internal.execution.BackendTotalRelation.LOWER_BOUND
        BackendTotalRelation.UNKNOWN -> me.ahoo.wow.query.internal.execution.BackendTotalRelation.UNKNOWN
    },
    when (consistency) {
        BackendPageConsistency.SAME_INPUT -> me.ahoo.wow.query.internal.execution.BackendPageConsistency.SAME_INPUT
        BackendPageConsistency.INDEPENDENT -> me.ahoo.wow.query.internal.execution.BackendPageConsistency.INDEPENDENT
        BackendPageConsistency.UNKNOWN -> me.ahoo.wow.query.internal.execution.BackendPageConsistency.UNKNOWN
    },
)

internal fun QueryPlan.isExperimentalRecordPlan(): Boolean =
    this is SingleQueryPlan ||
        this is StreamQueryPlan && limit is StreamLimit.Bounded ||
        this is me.ahoo.wow.query.internal.plan.PageQueryPlan ||
        this is CountQueryPlan
