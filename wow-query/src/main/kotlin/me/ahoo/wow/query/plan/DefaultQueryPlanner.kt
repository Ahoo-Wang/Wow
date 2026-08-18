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

package me.ahoo.wow.query.plan

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.ResultQueryRequest
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryDeadline
import me.ahoo.wow.query.invocation.QueryDeadlineExceededException
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.CombinedQueryPolicyResult
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections

internal class DefaultQueryPlanner private constructor(
    enabledCapabilities: Set<QueryCapabilityId>
) {
    private val enabledCapabilities: Set<QueryCapabilityId> =
        Collections.unmodifiableSet(LinkedHashSet(enabledCapabilities))

    @JvmSynthetic
    internal fun plan(
        invocation: QueryInvocation,
        policyResult: CombinedQueryPolicyResult,
        resolvedBackend: ResolvedQueryBackend
    ): Mono<QueryPlanV1> = Mono.defer {
        validateDescriptorAndReadiness(invocation, resolvedBackend)
        val expressionInspection = inspect(policyResult.securedExpression)
        validatePortableSemantics(expressionInspection, resolvedBackend)
        validateCapabilities(expressionInspection, policyResult, resolvedBackend)
        validateFields(expressionInspection.fields, invocation.schema, policyResult.constraints.fieldAccess)
        val authorizedResultShape = authorizeResultShape(invocation, policyResult.constraints.fieldAccess)
        val stableSort = stableSort(invocation, policyResult.constraints.fieldAccess)
        val effectiveBudget = QueryBudgetLimit.min(
            requestHint = null,
            systemLimit = invocation.admissionBudget,
            policyLimit = policyResult.constraints.maxBudget,
            backendLimit = resolvedBackend.descriptor.maxBudget
        )
        val effectiveDeadline = QueryDeadline.from(
            invocation.frozenInstant,
            effectiveBudget.timeout,
            QueryStage.PLANNING
        )
        val planCreation = Mono.fromCallable {
            validateBudget(invocation.request, effectiveBudget)
            createPlan(
                invocation = invocation,
                policyResult = policyResult,
                authorizedResultShape = authorizedResultShape,
                stableSort = stableSort,
                effectiveDeadline = effectiveDeadline,
                effectiveBudget = effectiveBudget,
                routeIdentity = resolvedBackend.routeIdentity
            )
        }
        invocation.deadlineGuard.enforce(planCreation, effectiveDeadline, QueryStage.PLANNING)
    }.onErrorMap(QueryDeadlineExceededException::class.java) { error ->
        QueryException(QueryErrorCode.DEADLINE_EXCEEDED, error.stage, QueryErrorReason.DEADLINE_REACHED)
    }

    private fun validateDescriptorAndReadiness(
        invocation: QueryInvocation,
        resolved: ResolvedQueryBackend
    ) {
        if (invocation.request.target.documentKind !in resolved.descriptor.documentKinds ||
            QueryPlanVersion.V1 !in resolved.descriptor.planVersions ||
            resolved.readinessSnapshot !is QueryBackendReadiness.Ready
        ) {
            throw backendNotReady()
        }
    }

    private fun validateBudget(
        request: me.ahoo.wow.api.query.gateway.QueryRequest,
        budget: QueryBudgetLimit
    ) {
        val maximum = budget.maxResults ?: return
        val requested = when (request) {
            is SingleQueryRequest<*> -> 1L
            is ListQueryRequest<*> -> request.limit.toLong().takeIf { it > 0 }
            is PageQueryRequest<*> -> request.page.size.toLong()
            is CountQueryRequest -> null
        }
        if (requested != null && requested > maximum) {
            throw QueryException(
                QueryErrorCode.BUDGET_EXCEEDED,
                QueryStage.PLANNING,
                QueryErrorReason.BUDGET_LIMIT_REACHED
            )
        }
    }

    private fun validatePortableSemantics(
        requested: ExpressionInspection,
        resolved: ResolvedQueryBackend
    ) {
        if (!resolved.descriptor.portableOperators.containsAll(requested.portableOperators) ||
            !resolved.descriptor.portableFeatures.containsAll(requested.portableFeatures) ||
            !resolved.descriptor.stringComparisonModes.containsAll(requested.stringComparisonModes)
        ) {
            unsupportedCapability()
        }
    }

    private fun validateCapabilities(
        requested: ExpressionInspection,
        policyResult: CombinedQueryPolicyResult,
        resolved: ResolvedQueryBackend
    ) {
        requested.capabilities.forEach { capability ->
            if (capability !in resolved.descriptor.capabilities || capability !in enabledCapabilities ||
                policyResult.constraints.capabilityAccess[capability] != CapabilityDecision.GRANT
            ) {
                unsupportedCapability()
            }
        }
        if (requested.nativeBackendIds.any { it != resolved.descriptor.backendId }) {
            unsupportedCapability()
        }
    }

    private fun validateFields(
        fields: Set<LogicalField>,
        schema: QuerySchemaView,
        access: QueryFieldAccess
    ) {
        if (!schema.fields.keys.containsAll(fields) || !access.allows(fields)) {
            fieldAccessDenied()
        }
    }

    private fun authorizeResultShape(
        invocation: QueryInvocation,
        access: QueryFieldAccess
    ): QueryPlanResultShape {
        val request = invocation.request
        if (request is CountQueryRequest) {
            return QueryPlanResultShape.Count
        }
        request as ResultQueryRequest<*>
        val projectable = invocation.schema.fields.values.filter { it.projectable }.mapTo(LinkedHashSet()) { it.path }
        val allowed = when (access) {
            QueryFieldAccess.Unrestricted -> projectable
            is QueryFieldAccess.Restricted -> projectable.intersect(access.fields).toCollection(LinkedHashSet())
        }
        return when (val resultShape = request.resultShape) {
            QueryResultShape.Dynamic -> QueryPlanResultShape.Dynamic(allowed)
            is QueryResultShape.Typed<*> -> {
                val fields = when (val projection = resultShape.projection) {
                    QueryProjection.All -> allowed
                    is QueryProjection.Include -> {
                        if (!allowed.containsAll(projection.fields)) {
                            fieldAccessDenied()
                        }
                        LinkedHashSet(projection.fields)
                    }

                    is QueryProjection.Exclude -> {
                        if (!allowed.containsAll(projection.fields)) {
                            fieldAccessDenied()
                        }
                        allowed.filterTo(LinkedHashSet()) { it !in projection.fields }
                    }
                }
                QueryPlanResultShape.Typed(resultShape.resultType, fields)
            }
        }
    }

    private fun stableSort(
        invocation: QueryInvocation,
        access: QueryFieldAccess
    ): List<QuerySort> {
        val requestedSort = requestedSort(invocation) ?: return emptyList()
        val uniqueFields = requestedSort.map(QuerySort::field).toSet()
        if (uniqueFields.size != requestedSort.size) {
            invalidQuery()
        }
        validateSortFields(requestedSort, invocation.schema, access)
        val identity = when (invocation.request.target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> LogicalField("aggregateId")
            QueryDocumentKind.EVENT_STREAM -> LogicalField("id")
        }
        if (identity in uniqueFields) {
            return immutableList(requestedSort)
        }
        val identitySchema = invocation.schema.field(identity)
        if (identitySchema?.system != true || !identitySchema.sortable || !access.allows(setOf(identity))) {
            fieldAccessDenied()
        }
        return immutableList(requestedSort + QuerySort(identity, QuerySortDirection.ASC))
    }

    private fun requestedSort(invocation: QueryInvocation): List<QuerySort>? =
        when (val request = invocation.request) {
            is SingleQueryRequest<*> -> request.sort
            is ListQueryRequest<*> -> request.sort
            is PageQueryRequest<*> -> request.sort
            is CountQueryRequest -> null
        }

    private fun validateSortFields(
        requestedSort: List<QuerySort>,
        schema: QuerySchemaView,
        access: QueryFieldAccess
    ) {
        requestedSort.forEach { sort ->
            if (schema.field(sort.field)?.sortable != true || !access.allows(setOf(sort.field))) {
                fieldAccessDenied()
            }
        }
    }

    private fun createPlan(
        invocation: QueryInvocation,
        policyResult: CombinedQueryPolicyResult,
        authorizedResultShape: QueryPlanResultShape,
        stableSort: List<QuerySort>,
        effectiveDeadline: Instant?,
        effectiveBudget: QueryBudgetLimit,
        routeIdentity: QueryBackendRouteIdentity
    ): QueryPlanV1 {
        val provenance = LinkedHashMap(invocation.expressionProvenance)
        require(provenance.put(QueryProvenance.MANDATORY_POLICY, policyResult.mandatoryExpression) == null) {
            "Invocation provenance already contains a mandatory policy contribution."
        }
        val state = PlanState(
            target = invocation.request.target,
            securedExpression = policyResult.securedExpression,
            expressionProvenance = provenance,
            authorizedResultShape = authorizedResultShape,
            sort = stableSort,
            effectiveDeadline = effectiveDeadline,
            effectiveBudget = effectiveBudget,
            correlationId = invocation.scope.correlationId,
            routeIdentity = routeIdentity
        )
        return when (val request = invocation.request) {
            is SingleQueryRequest<*> -> SinglePlan<Any>(state)
            is ListQueryRequest<*> -> ListPlan<Any>(state, request.limit)
            is PageQueryRequest<*> -> PagePlan<Any>(state, request.page)
            is CountQueryRequest -> CountPlan(state)
        }
    }

    private fun inspect(expression: QueryExpression): ExpressionInspection {
        val operators = LinkedHashSet<PortableOperator>()
        val features = LinkedHashSet<QueryPortableFeature>()
        val stringComparisonModes = LinkedHashSet<StringComparisonMode>()
        val capabilities = LinkedHashSet<QueryCapabilityId>()
        val nativeBackendIds = LinkedHashSet<String>()
        val fields = LinkedHashSet<LogicalField>()
        val pending = ArrayDeque<ExpressionFrame>()
        pending += ExpressionFrame(expression, null)
        while (pending.isNotEmpty()) {
            val frame = pending.removeLast()
            when (val current = frame.expression) {
                MatchAll,
                MatchNone -> Unit

                is LogicalExpression -> current.operands.forEach {
                    pending += ExpressionFrame(it, frame.relativeTo)
                }
                is PortableLogicalExpression -> current.operands.forEach {
                    pending += ExpressionFrame(it, frame.relativeTo)
                }
                is PredicateExpression -> {
                    operators += current.operator
                    if (current.operator in STRING_COMPARISON_OPERATORS) {
                        stringComparisonModes += current.stringComparison
                    }
                    fields += resolvePath(frame.relativeTo, current.field)
                }

                is ElementMatchExpression -> {
                    features += QueryPortableFeature.ELEMENT_MATCH
                    val field = resolvePath(frame.relativeTo, current.field)
                    fields += field
                    pending += ExpressionFrame(current.predicate, field)
                }

                is FullTextExpression -> {
                    capabilities += current.capabilityId
                    fields += current.fields.map { resolvePath(frame.relativeTo, it) }
                }

                is NativeExpression -> {
                    capabilities += current.capabilityId
                    nativeBackendIds += current.backendId
                    fields += current.declaredFields.map { resolvePath(frame.relativeTo, it) }
                }
                is RelativeTimeExpression -> invalidQuery()
            }
        }
        return ExpressionInspection(
            operators,
            features,
            stringComparisonModes,
            capabilities,
            nativeBackendIds,
            fields
        )
    }

    private fun resolvePath(relativeTo: LogicalField?, field: LogicalField): LogicalField =
        if (relativeTo == null) field else LogicalField("${relativeTo.value}.${field.value}")

    private fun QueryFieldAccess.allows(fields: Set<LogicalField>): Boolean = when (this) {
        QueryFieldAccess.Unrestricted -> true
        is QueryFieldAccess.Restricted -> this.fields.containsAll(fields)
    }

    private fun backendNotReady(): QueryException = QueryException(
        QueryErrorCode.BACKEND_NOT_READY,
        QueryStage.PLANNING,
        QueryErrorReason.BACKEND_UNAVAILABLE
    )

    private fun unsupportedCapability(): Nothing = throw QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED
    )

    private fun fieldAccessDenied(): Nothing = throw QueryException(
        QueryErrorCode.POLICY_DENIED,
        QueryStage.PLANNING,
        QueryErrorReason.FIELD_ACCESS_DENIED
    )

    private fun invalidQuery(): Nothing = throw QueryException(
        QueryErrorCode.INVALID_QUERY,
        QueryStage.PLANNING,
        QueryErrorReason.INVALID_REQUEST
    )

    private fun <T> immutableList(source: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(source))

    private class ExpressionInspection(
        val portableOperators: Set<PortableOperator>,
        val portableFeatures: Set<QueryPortableFeature>,
        val stringComparisonModes: Set<StringComparisonMode>,
        val capabilities: Set<QueryCapabilityId>,
        val nativeBackendIds: Set<String>,
        val fields: Set<LogicalField>
    )

    private class ExpressionFrame(
        val expression: QueryExpression,
        val relativeTo: LogicalField?
    )

    private class PlanState(
        val target: me.ahoo.wow.api.query.gateway.QueryTarget,
        val securedExpression: QueryExpression,
        expressionProvenance: Map<QueryProvenance, QueryExpression>,
        val authorizedResultShape: QueryPlanResultShape,
        sort: List<QuerySort>,
        val effectiveDeadline: Instant?,
        val effectiveBudget: QueryBudgetLimit,
        val correlationId: String,
        val routeIdentity: QueryBackendRouteIdentity
    ) {
        val expressionProvenance: Map<QueryProvenance, QueryExpression> =
            Collections.unmodifiableMap(LinkedHashMap(expressionProvenance))
        val sort: List<QuerySort> = Collections.unmodifiableList(ArrayList(sort))
    }

    private abstract class AbstractPlan(private val state: PlanState) : QueryPlanV1 {
        override val version: QueryPlanVersion get() = QueryPlanVersion.V1
        override val target get() = state.target
        override val securedExpression get() = state.securedExpression
        override val expressionProvenance get() = state.expressionProvenance
        override val authorizedResultShape get() = state.authorizedResultShape
        override val sort get() = state.sort
        override val effectiveDeadline get() = state.effectiveDeadline
        override val effectiveBudget get() = state.effectiveBudget
        override val correlationId get() = state.correlationId
        override val routeIdentity get() = state.routeIdentity

        override fun toString(): String =
            "QueryPlanV1(version=${QueryPlanVersion.V1.value}, target=<redacted>, securedExpression=<redacted>, " +
                "expressionProvenance=<redacted>, authorizedResultShape=<redacted>, sort=<redacted>, " +
                "effectiveDeadline=<redacted>, effectiveBudget=<redacted>, correlationId=<redacted>, " +
                "routeIdentity=<redacted>)"
    }

    private class SinglePlan<R : Any>(state: PlanState) : AbstractPlan(state), SingleQueryPlanV1<R>

    private class ListPlan<R : Any>(state: PlanState, override val limit: Int) :
        AbstractPlan(state),
        ListQueryPlanV1<R>

    private class PagePlan<R : Any>(state: PlanState, override val page: me.ahoo.wow.api.query.gateway.QueryPageSpec) :
        AbstractPlan(state),
        PageQueryPlanV1<R>

    private class CountPlan(state: PlanState) : AbstractPlan(state), CountQueryPlanV1

    internal companion object {
        private val STRING_COMPARISON_OPERATORS = setOf(
            PortableOperator.CONTAINS,
            PortableOperator.STARTS_WITH,
            PortableOperator.ENDS_WITH
        )

        @JvmSynthetic
        internal fun create(
            enabledCapabilities: Set<QueryCapabilityId>
        ): DefaultQueryPlanner = DefaultQueryPlanner(enabledCapabilities)
    }
}
