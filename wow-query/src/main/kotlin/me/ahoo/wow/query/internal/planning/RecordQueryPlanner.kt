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

import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.model.RecordResultShape
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedRecordQuery
import me.ahoo.wow.query.internal.normalization.NormalizedSort
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.EnforcedFilter
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.PageWindow
import me.ahoo.wow.query.internal.plan.PlannedProjection
import me.ahoo.wow.query.internal.plan.PlannedSort
import me.ahoo.wow.query.internal.plan.PlannedSortOrigin
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.RecordQueryPlan
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.RequiredConsistency
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamLimit
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.plan.TotalMode
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.value.NonEmptyList
import java.util.Collections

internal class RecordQueryPlanner(
    private val invocation: NormalizedQueryInvocation,
    private val schema: QueryDocumentSchema,
    private val conditionPlanner: QueryConditionPlanner,
    private val constraints: PlanningConstraints,
    private val mandatory: ValidatedMandatory,
) {
    private val issues = mutableListOf<QueryRejection>()

    fun plan(input: NormalizedQueryInput): PlanningDecision {
        val queryPlan =
            when (input) {
                is NormalizedQueryInput.Single -> planRecord(input.query) { common ->
                    SingleQueryPlan.create(
                        invocation.target,
                        schema.contractId,
                        common.filter,
                        invocation.resultShape.toRecordShape(),
                        common.projection,
                        common.sort,
                        common.capabilities,
                        common.semanticTier,
                    )
                }

                is NormalizedQueryInput.Stream -> planStream(input)
                is NormalizedQueryInput.Page -> planPage(input)
                is NormalizedQueryInput.Count -> planCount(input)
                is NormalizedQueryInput.Analytics -> error("Analytics input must use AnalyticsQueryPlanner.")
            }
        if (issues.isNotEmpty()) {
            return PlanningDecision.LegacyFallback(
                checkNotNull(NonEmptyList.from(issues.sortedWith(REJECTION_ORDER))),
                mandatory,
            )
        }
        return PlanningDecision.Planned(checkNotNull(queryPlan))
    }

    private fun planStream(input: NormalizedQueryInput.Stream): QueryPlan? {
        val path = QueryRejectionPath.ROOT.property("input").property("limit")
        if (input.limit < 0) {
            rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, QueryRejectionCode.INVALID_LIMIT)
        }
        if (input.limit == 0 && constraints.streamConstraint == StreamPlanningConstraint.BoundedOnly) {
            rejectQuery(
                QueryRejectionCategory.BUDGET_EXCEEDED,
                path,
                QueryRejectionCode.UNBOUNDED_STREAM_DISALLOWED,
            )
        }
        return planRecord(input.query) { common ->
            StreamQueryPlan.create(
                invocation.target,
                schema.contractId,
                common.filter,
                invocation.resultShape.toRecordShape(),
                common.projection,
                common.sort,
                if (input.limit == 0) StreamLimit.Unbounded else StreamLimit.Bounded(input.limit),
                common.capabilities,
                common.semanticTier,
            )
        }
    }

    private fun planPage(input: NormalizedQueryInput.Page): QueryPlan? {
        validatePage(input)
        return planRecord(input.query) { common ->
            PageQueryPlan.create(
                invocation.target,
                schema.contractId,
                common.filter,
                invocation.resultShape.toRecordShape(),
                common.projection,
                common.sort,
                PageWindow(input.page.offset, input.page.size),
                TotalMode.EXACT,
                RequiredConsistency.SAME_INPUT,
                common.capabilities,
                common.semanticTier,
            )
        }
    }

    private fun validatePage(input: NormalizedQueryInput.Page) {
        val path = QueryRejectionPath.ROOT.property("input").property("page")
        val expectedOffset =
            if (input.page.index < 1 || input.page.size <= 0) {
                null
            } else {
                Math.multiplyExact(input.page.index.toLong() - 1, input.page.size.toLong())
            }
        if (input.page.offset < 0 || expectedOffset == null || input.page.offset != expectedOffset) {
            rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, QueryRejectionCode.INVALID_PAGE)
        }
        val maximumWindow = (constraints.pageConstraint as? PagePlanningConstraint.MaximumWindow)?.value ?: return
        val endExclusive =
            try {
                Math.addExact(input.page.offset, input.page.size.toLong())
            } catch (error: ArithmeticException) {
                rejectQuery(
                    QueryRejectionCategory.BUDGET_EXCEEDED,
                    path,
                    QueryRejectionCode.PAGE_WINDOW_EXCEEDED,
                    error,
                )
            }
        if (endExclusive > maximumWindow) {
            rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, QueryRejectionCode.PAGE_WINDOW_EXCEEDED)
        }
    }

    private fun planCount(input: NormalizedQueryInput.Count): QueryPlan? {
        val user = planCompatible {
            conditionPlanner.plan(
                input.userCondition,
                QueryRejectionPath.ROOT.property("input").property("userCondition"),
                mandatory = false,
            )
        } ?: return null
        return CountQueryPlan.create(
            invocation.target,
            schema.contractId,
            EnforcedFilter(user.condition, mandatory.condition),
            mergeCapabilities(user.requiredCapabilities, mandatory.requiredCapabilities),
            user.semanticTier.max(mandatory.semanticTier),
        )
    }

    private fun planRecord(
        query: NormalizedRecordQuery,
        factory: (RecordCommon) -> RecordQueryPlan,
    ): QueryPlan? {
        val queryPath = QueryRejectionPath.ROOT.property("input").property("query")
        val user = planCompatible {
            conditionPlanner.plan(query.userCondition, queryPath.property("condition"), mandatory = false)
        }
        val projection = planProjection(query.projection, queryPath.property("projection"))
        val sort = planSort(query.sort, queryPath.property("sort"))
        if (user == null || projection == null || sort == null) {
            return null
        }
        return factory(
            RecordCommon(
                EnforcedFilter(user.condition, mandatory.condition),
                projection.projection,
                sort.sort,
                mergeCapabilities(
                    user.requiredCapabilities,
                    mandatory.requiredCapabilities,
                    projection.capabilities,
                    sort.capabilities,
                ),
                user.semanticTier.max(mandatory.semanticTier),
            ),
        )
    }

    private fun planProjection(
        projection: NormalizedProjection,
        path: QueryRejectionPath,
    ): ProjectionResult? {
        if (invocation.resultShape == QueryResultShape.TYPED && projection != NormalizedProjection.All) {
            return handleTypedProjection(path)
        }
        if (projection is NormalizedProjection.Mixed) {
            rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, QueryRejectionCode.INVALID_PROJECTION)
        }
        if (projection == NormalizedProjection.All) {
            return ProjectionResult(PlannedProjection.All, RequiredCapabilities())
        }
        return planCompatible {
            val fields = projection.fields()
            val resolved = fields.mapIndexed { index, field ->
                val fieldPath = path.property("fields").index(index)
                conditionPlanner.resolveField(field, fieldPath).also { resolvedField ->
                    requireFieldCapability(
                        schema,
                        resolvedField,
                        FieldCapability.PROJECTABLE,
                        fieldPath,
                    )
                }
            }.distinct().sortedBy(QueryFieldId::stableKey)
            val nonEmpty = checkNotNull(NonEmptyList.from(resolved))
            val planned =
                if (projection is NormalizedProjection.Include) {
                    PlannedProjection.Include(nonEmpty)
                } else {
                    PlannedProjection.Exclude(nonEmpty)
                }
            ProjectionResult(
                planned,
                RequiredCapabilities(resolved.associateWith { setOf(FieldCapability.PROJECTABLE) }),
            )
        }
    }

    private fun handleTypedProjection(path: QueryRejectionPath): ProjectionResult? {
        val rejection = QueryRejection(
            QueryRejectionCategory.INVALID_QUERY,
            path,
            QueryRejectionCode.TYPED_PROJECTION_NOT_ALLOWED,
        )
        if (constraints.validationMode == QueryValidationMode.STRICT) {
            throw QueryRejectedException(rejection)
        }
        issues += rejection
        return null
    }

    private fun planSort(
        sort: List<NormalizedSort>,
        path: QueryRejectionPath,
    ): SortResult? = planCompatible {
        val planned = mutableListOf<PlannedSort>()
        val seen = mutableSetOf<QueryFieldId>()
        sort.forEachIndexed { index, item ->
            val itemPath = path.index(index)
            val field = conditionPlanner.resolveField(item.field, itemPath.property("field"))
            if (!seen.add(field)) {
                return@planCompatible handleDuplicateSort(itemPath.property("field"))
            }
            requireFieldCapability(schema, field, FieldCapability.SORTABLE, itemPath.property("field"))
            planned += PlannedSort(field, item.direction, PlannedSortOrigin.USER)
        }
        appendIdentityTieBreaker(planned, seen, path)
        val requirements = planned.associate { it.field to setOf(FieldCapability.SORTABLE) }
        SortResult(Collections.unmodifiableList(planned), RequiredCapabilities(requirements))
    }

    private fun handleDuplicateSort(path: QueryRejectionPath): SortResult? {
        val rejection = QueryRejection(
            QueryRejectionCategory.INVALID_QUERY,
            path,
            QueryRejectionCode.DUPLICATE_SORT,
        )
        if (constraints.validationMode == QueryValidationMode.STRICT) {
            throw QueryRejectedException(rejection)
        }
        issues += rejection
        return null
    }

    private fun appendIdentityTieBreaker(
        sort: MutableList<PlannedSort>,
        seen: Set<QueryFieldId>,
        path: QueryRejectionPath,
    ) {
        if (invocation.operation != QueryOperation.PAGE || constraints.validationMode != QueryValidationMode.STRICT) {
            return
        }
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        if (identity in seen) {
            return
        }
        requireFieldCapability(schema, identity, FieldCapability.SORTABLE, path)
        sort += PlannedSort(identity, NormalizedSortDirection.ASC, PlannedSortOrigin.STABILITY_TIE_BREAKER)
    }

    private fun <T> planCompatible(block: () -> T): T? =
        try {
            block()
        } catch (error: QueryRejectedException) {
            if (constraints.validationMode == QueryValidationMode.STRICT ||
                error.rejection.category != QueryRejectionCategory.UNSUPPORTED_FEATURE
            ) {
                throw error
            }
            issues += error.rejection
            null
        }

    private fun NormalizedProjection.fields(): List<me.ahoo.wow.query.internal.normalization.LogicalField.Path> =
        when (this) {
            is NormalizedProjection.Include -> fields.values
            is NormalizedProjection.Exclude -> fields.values
            NormalizedProjection.All,
            is NormalizedProjection.Mixed,
            -> error("Projection branch was already handled.")
        }

    private fun QueryResultShape.toRecordShape(): RecordResultShape =
        when (this) {
            QueryResultShape.TYPED -> RecordResultShape.TYPED
            QueryResultShape.DYNAMIC -> RecordResultShape.DYNAMIC
            QueryResultShape.COUNT,
            QueryResultShape.ANALYTICS,
            -> error("Not a record result shape: $this")
        }

    private data class RecordCommon(
        val filter: EnforcedFilter,
        val projection: PlannedProjection,
        val sort: List<PlannedSort>,
        val capabilities: RequiredCapabilities,
        val semanticTier: SemanticTier,
    )

    private data class ProjectionResult(
        val projection: PlannedProjection,
        val capabilities: RequiredCapabilities,
    )

    private data class SortResult(
        val sort: List<PlannedSort>,
        val capabilities: RequiredCapabilities,
    )

    private companion object {
        val REJECTION_ORDER = compareBy<QueryRejection>({ it.path.toString() }, { it.code.name })
    }
}
