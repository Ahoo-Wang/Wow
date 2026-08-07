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

import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.policy.QueryExecutionContext
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.value.NonEmptyList

internal data class QueryFallback(
    val target: QueryTarget,
    val operation: QueryOperation,
    val executionMode: QueryExecutionMode,
    val issues: NonEmptyList<QueryRejection>,
)

internal sealed interface QueryExecutionRoute {
    data class Planned(
        val registry: QueryBackendRegistry,
        val plan: QueryPlan,
    ) : QueryExecutionRoute

    data class Legacy(
        val binding: LegacyExecutionBinding,
        val input: LegacyCompilationInput,
        val fallback: QueryFallback? = null,
    ) : QueryExecutionRoute

    data class Shadow(
        val legacyBinding: LegacyExecutionBinding,
        val legacyInput: LegacyCompilationInput,
        val plannedRegistry: QueryBackendRegistry,
        val plan: QueryPlan,
    ) : QueryExecutionRoute
}

internal class QueryExecutionRouteResolver(
    private val plannedRegistry: QueryBackendRegistry,
    private val legacyRegistry: LegacyBackendRegistry,
) {
    fun resolve(
        context: QueryExecutionContext,
        invocation: NormalizedQueryInvocation,
        schema: QueryDocumentSchema,
        decision: PlanningDecision,
    ): QueryExecutionRoute {
        validateInput(context, invocation, schema, decision)
        val legacyInput = LegacyCompilationInput(invocation, schema, decision)
        return when (context.executionMode) {
            QueryExecutionMode.LEGACY -> legacyRoute(
                invocation,
                legacyInput,
                decision,
                QueryExecutionMode.LEGACY,
            )
            QueryExecutionMode.SHADOW -> shadowRoute(invocation, legacyInput, decision)
            QueryExecutionMode.PLANNED -> plannedRoute(invocation, legacyInput, decision)
        }
    }

    private fun legacyRoute(
        invocation: NormalizedQueryInvocation,
        input: LegacyCompilationInput,
        decision: PlanningDecision,
        executionMode: QueryExecutionMode,
    ): QueryExecutionRoute {
        rejectLegacyAnalytics(invocation)
        return QueryExecutionRoute.Legacy(
            legacyRegistry.resolve(invocation.target),
            input,
            decision.fallback(invocation, executionMode),
        )
    }

    private fun shadowRoute(
        invocation: NormalizedQueryInvocation,
        legacyInput: LegacyCompilationInput,
        decision: PlanningDecision,
    ): QueryExecutionRoute {
        rejectLegacyAnalytics(invocation)
        val legacy = legacyRegistry.resolve(invocation.target)
        return when (decision) {
            is PlanningDecision.Planned -> QueryExecutionRoute.Shadow(
                legacy,
                legacyInput,
                plannedRegistry,
                decision.plan,
            )

            is PlanningDecision.LegacyFallback -> legacyRoute(
                invocation,
                legacyInput,
                decision,
                QueryExecutionMode.SHADOW,
            )
        }
    }

    private fun plannedRoute(
        invocation: NormalizedQueryInvocation,
        legacyInput: LegacyCompilationInput,
        decision: PlanningDecision,
    ): QueryExecutionRoute =
        when (decision) {
            is PlanningDecision.Planned -> QueryExecutionRoute.Planned(
                plannedRegistry,
                decision.plan,
            )

            is PlanningDecision.LegacyFallback -> {
                legacyRoute(
                    invocation,
                    legacyInput,
                    decision,
                    QueryExecutionMode.PLANNED,
                )
            }
        }

    private fun PlanningDecision.fallback(
        invocation: NormalizedQueryInvocation,
        executionMode: QueryExecutionMode,
    ): QueryFallback? = when (this) {
        is PlanningDecision.Planned -> null
        is PlanningDecision.LegacyFallback -> QueryFallback(
            invocation.target,
            invocation.operation,
            executionMode,
            issues,
        )
    }

    private fun validateInput(
        context: QueryExecutionContext,
        invocation: NormalizedQueryInvocation,
        schema: QueryDocumentSchema,
        decision: PlanningDecision,
    ) {
        if (context.target != invocation.target || schema.target != invocation.target) {
            rejectQuery(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT.property("target"),
                QueryRejectionCode.TARGET_SCHEMA_MISMATCH,
            )
        }
        if (context.validationMode == QueryValidationMode.STRICT && decision is PlanningDecision.LegacyFallback) {
            rejectQuery(
                QueryRejectionCategory.INTERNAL_FAILURE,
                QueryRejectionPath.ROOT.property("execution"),
                QueryRejectionCode.EXECUTION_DECISION_INVALID,
            )
        }
    }

    private fun rejectLegacyAnalytics(invocation: NormalizedQueryInvocation) {
        if (invocation.operation == QueryOperation.ANALYZE) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionPath.ROOT.property("executionContext").property("executionMode"),
                QueryRejectionCode.EXECUTION_MODE_UNSUPPORTED,
            )
        }
    }
}
