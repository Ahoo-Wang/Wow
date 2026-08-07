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

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.execution.BackendPage
import me.ahoo.wow.query.internal.execution.BackendPageConsistency
import me.ahoo.wow.query.internal.execution.BackendRecord
import me.ahoo.wow.query.internal.execution.BackendTotalRelation
import me.ahoo.wow.query.internal.execution.LegacyCompilationInput
import me.ahoo.wow.query.internal.execution.LegacyCompiledQuery
import me.ahoo.wow.query.internal.execution.LegacyQueryBackend
import me.ahoo.wow.query.internal.execution.LegacyQueryCompiler
import me.ahoo.wow.query.internal.execution.QueryExecutionOptions
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedRecordQuery
import me.ahoo.wow.query.internal.normalization.NormalizedSort
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.plan.PlannedSort
import me.ahoo.wow.query.internal.plan.RecordQueryPlan
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.SchemaContractId
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal sealed interface LegacyWireQuery {
    val condition: Condition

    data class Single(val query: SingleQuery) : LegacyWireQuery {
        override val condition: Condition = query.condition
    }

    data class Stream(val query: ListQuery) : LegacyWireQuery {
        override val condition: Condition = query.condition
    }

    data class Page(val query: PagedQuery) : LegacyWireQuery {
        override val condition: Condition = query.condition
    }

    data class Count(override val condition: Condition) : LegacyWireQuery
}

internal class LegacyDynamicCompiledQuery(
    override val target: QueryTarget,
    override val operation: QueryOperation,
    override val schemaContractId: SchemaContractId,
    override val loweringAttestation: me.ahoo.wow.query.internal.execution.LegacyLoweringAttestation,
    val wireQuery: LegacyWireQuery,
    val matchNone: Boolean,
    val identityField: String,
    val outputProjection: NormalizedProjection,
) : LegacyCompiledQuery

internal class LegacyDynamicQueryCompiler(
    private val target: QueryTarget,
    private val dialect: QueryLegacyDialect,
    private val identityField: String,
) : LegacyQueryCompiler<LegacyDynamicCompiledQuery> {
    override fun compile(input: LegacyCompilationInput): LegacyDynamicCompiledQuery {
        val invocation = input.invocation
        if (invocation.target != target) {
            rejectLegacyLowering()
        }
        val lowered = LegacyConditionLowerer(
            dialect,
            when (target.documentKind) {
                QueryDocumentKind.SNAPSHOT -> LegacyDeletionMode.SNAPSHOT
                QueryDocumentKind.EVENT_STREAM -> LegacyDeletionMode.NONE
            },
        ).lower(
            invocation.userCondition(),
            invocation.deletionScope(),
            input.enforcementRequirements.mandatoryCondition,
        )
        val record = invocation.recordQuery()
        val sort = input.decision.plannedRecordSort(identityField) ?: record.toSort()
        val wireQuery = when (val normalizedInput = invocation.input) {
            is NormalizedQueryInput.Single -> LegacyWireQuery.Single(
                SingleQuery(
                    lowered.condition,
                    record.requireProjectionWithIdentity(identityField),
                    sort,
                ),
            )

            is NormalizedQueryInput.Stream -> LegacyWireQuery.Stream(
                ListQuery(
                    lowered.condition,
                    record.requireProjectionWithIdentity(identityField),
                    sort,
                    normalizedInput.limit,
                ),
            )

            is NormalizedQueryInput.Page -> LegacyWireQuery.Page(
                PagedQuery(
                    lowered.condition,
                    record.requireProjectionWithIdentity(identityField),
                    sort,
                    normalizedInput.page.toLegacyPagination(),
                ),
            )

            is NormalizedQueryInput.Count -> LegacyWireQuery.Count(lowered.condition)
            is NormalizedQueryInput.Analytics -> rejectLegacyLowering()
        }
        return LegacyDynamicCompiledQuery(
            target,
            invocation.operation,
            input.schema.contractId,
            input.attestLowering(
                input.enforcementRequirements.deletionScope,
                input.enforcementRequirements.mandatoryCondition,
            ),
            wireQuery,
            lowered.matchNone,
            identityField,
            record?.projection ?: NormalizedProjection.All,
        )
    }
}

internal class LegacyDynamicQueryBackend(
    private val queryService: QueryService<*>,
    limits: QueryAdmissionLimits = QueryAdmissionLimits.DEFAULT,
) : LegacyQueryBackend<LegacyDynamicCompiledQuery> {
    private val resultSnapshotter = LegacyResultSnapshotter(limits)

    override fun single(
        query: LegacyDynamicCompiledQuery,
        options: QueryExecutionOptions,
    ): Mono<BackendRecord> {
        if (query.matchNone) {
            return Mono.empty()
        }
        val wire = query.wireQuery as? LegacyWireQuery.Single ?: rejectLegacyLowering()
        return queryService.dynamicSingle(wire.query).map { result -> resultSnapshotter.snapshot(query, result) }
    }

    override fun stream(
        query: LegacyDynamicCompiledQuery,
        options: QueryExecutionOptions,
    ): Flux<BackendRecord> {
        if (query.matchNone) {
            return Flux.empty()
        }
        val wire = query.wireQuery as? LegacyWireQuery.Stream ?: rejectLegacyLowering()
        return queryService.dynamicList(wire.query).map { result -> resultSnapshotter.snapshot(query, result) }
    }

    override fun page(
        query: LegacyDynamicCompiledQuery,
        options: QueryExecutionOptions,
    ): Mono<BackendPage> {
        if (query.matchNone) {
            return Mono.just(emptyBackendPage())
        }
        val wire = query.wireQuery as? LegacyWireQuery.Page ?: rejectLegacyLowering()
        return queryService.dynamicPaged(wire.query).map { result -> result.toBackendPage(query) }
    }

    override fun count(query: LegacyDynamicCompiledQuery, options: QueryExecutionOptions): Mono<Long> {
        if (query.matchNone) {
            return Mono.just(0)
        }
        val wire = query.wireQuery as? LegacyWireQuery.Count ?: rejectLegacyLowering()
        return queryService.count(wire.condition)
    }

    private fun PagedList<DynamicDocument>.toBackendPage(query: LegacyDynamicCompiledQuery): BackendPage =
        BackendPage(
            list.map { result -> resultSnapshotter.snapshot(query, result) },
            total,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.INDEPENDENT,
        )

    private fun emptyBackendPage(): BackendPage =
        BackendPage(
            emptyList(),
            0,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.INDEPENDENT,
        )
}

private fun me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation.userCondition() =
    when (val normalizedInput = input) {
        is NormalizedQueryInput.Single -> normalizedInput.query.userCondition
        is NormalizedQueryInput.Stream -> normalizedInput.query.userCondition
        is NormalizedQueryInput.Page -> normalizedInput.query.userCondition
        is NormalizedQueryInput.Count -> normalizedInput.userCondition
        is NormalizedQueryInput.Analytics -> rejectLegacyLowering()
    }

private fun me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation.deletionScope() =
    when (val normalizedInput = input) {
        is NormalizedQueryInput.Single -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Stream -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Page -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Count -> normalizedInput.deletionScope
        is NormalizedQueryInput.Analytics -> NormalizedDeletionScope.EXPLICIT
    }

private fun me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation.recordQuery(): NormalizedRecordQuery? =
    when (val normalizedInput = input) {
        is NormalizedQueryInput.Single -> normalizedInput.query
        is NormalizedQueryInput.Stream -> normalizedInput.query
        is NormalizedQueryInput.Page -> normalizedInput.query
        is NormalizedQueryInput.Count,
        is NormalizedQueryInput.Analytics,
        -> null
    }

private fun NormalizedRecordQuery?.requireProjectionWithIdentity(identityField: String): Projection {
    val projection = this?.projection ?: return Projection.ALL
    return when (projection) {
        NormalizedProjection.All -> Projection.ALL
        is NormalizedProjection.Include -> Projection(
            include = (projection.fields.values.map(LogicalField.Path::toLegacyField) + identityField).distinct(),
        )

        is NormalizedProjection.Exclude -> Projection(
            exclude = projection.fields.values.map(LogicalField.Path::toLegacyField).filterNot(identityField::equals),
        )

        is NormalizedProjection.Mixed -> Projection(
            include = (projection.include.values.map(LogicalField.Path::toLegacyField) + identityField).distinct(),
            exclude = projection.exclude.values.map(LogicalField.Path::toLegacyField).filterNot(identityField::equals),
        )
    }
}

private fun NormalizedRecordQuery?.toSort(): List<Sort> = this?.sort.orEmpty().map(NormalizedSort::toLegacy)

private fun PlanningDecision.plannedRecordSort(identityField: String): List<Sort>? =
    ((this as? PlanningDecision.Planned)?.plan as? RecordQueryPlan)?.sort?.map { sort ->
        sort.toLegacy(identityField)
    }

private fun PlannedSort.toLegacy(identityField: String): Sort =
    Sort(
        field = when (val plannedField = field) {
            is me.ahoo.wow.query.internal.schema.QueryFieldId.Path -> plannedField.segments.joinToString(".")
            is me.ahoo.wow.query.internal.schema.QueryFieldId.System ->
                if (plannedField.kind == me.ahoo.wow.query.internal.normalization.SystemFieldKind.IDENTITY) {
                    identityField
                } else {
                    rejectLegacyLowering()
                }
        },
        direction = direction.toLegacy(),
    )

private fun NormalizedSort.toLegacy(): Sort =
    Sort(
        field = (field as? LogicalField.Path)?.toLegacyField() ?: rejectLegacyLowering(),
        direction = direction.toLegacy(),
    )

private fun NormalizedSortDirection.toLegacy(): Sort.Direction =
    when (this) {
        NormalizedSortDirection.ASC -> Sort.Direction.ASC
        NormalizedSortDirection.DESC -> Sort.Direction.DESC
    }

private fun LogicalField.Path.toLegacyField(): String = segments.joinToString(".")

private fun me.ahoo.wow.query.internal.normalization.NormalizedPage.toLegacyPagination(): Pagination {
    val legacyOffset = runCatching { Pagination.offset(index, size) }.getOrElse { rejectLegacyLowering() }
    if (offset > Int.MAX_VALUE || legacyOffset.toLong() != offset) {
        rejectLegacyLowering()
    }
    return Pagination(index, size)
}

internal fun rejectLegacyLowering(): Nothing = rejectQuery(
    QueryRejectionCategory.UNSUPPORTED_FEATURE,
    QueryRejectionPath.ROOT.property("execution").property("legacy"),
    QueryRejectionCode.LEGACY_LOWERING_UNSUPPORTED,
)
