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

package me.ahoo.wow.query.internal.policy

import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.planning.AnalyticsPlanningConstraint
import me.ahoo.wow.query.internal.planning.FieldAccess
import me.ahoo.wow.query.internal.planning.PagePlanningConstraint
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.QueryFieldConstraint
import me.ahoo.wow.query.internal.planning.ResultPlanningConstraint
import me.ahoo.wow.query.internal.planning.SearchScopeAccess
import me.ahoo.wow.query.internal.planning.StreamPlanningConstraint
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import reactor.core.publisher.Mono

internal data class QueryPolicyInput(
    val executionContext: QueryExecutionContext,
    val invocation: NormalizedQueryInvocation,
    val schema: QueryDocumentSchema,
)

internal enum class QueryPolicyDenial {
    TENANT_MISMATCH,
    OWNER_MISMATCH,
    SPACE_MISMATCH,
    PURPOSE_NOT_ALLOWED,
    POLICY_RULE,
}

internal sealed interface QueryPolicyDecision {
    data class Allow(val allowance: QueryPolicyAllowance) : QueryPolicyDecision

    data class Deny(val reason: QueryPolicyDenial) : QueryPolicyDecision
}

internal fun interface QueryPolicy {
    fun decide(input: QueryPolicyInput): Mono<QueryPolicyDecision>
}

internal class QueryPolicyAllowance private constructor(
    val mandatoryCondition: NormalizedCondition,
    val fieldConstraint: QueryFieldConstraint,
    val resultConstraint: ResultPlanningConstraint,
    val streamConstraint: StreamPlanningConstraint,
    val pageConstraint: PagePlanningConstraint,
    val analyticsConstraint: AnalyticsPlanningConstraint,
) {
    fun toPlanningConstraints(
        validationMode: QueryValidationMode,
    ): PlanningConstraints =
        PlanningConstraints(
            validationMode = validationMode,
            mandatoryCondition = mandatoryCondition,
            fieldConstraint = fieldConstraint,
            resultConstraint = resultConstraint,
            streamConstraint = streamConstraint,
            pageConstraint = pageConstraint,
            analyticsConstraint = analyticsConstraint,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryPolicyAllowance &&
            mandatoryCondition == other.mandatoryCondition &&
            fieldConstraint == other.fieldConstraint &&
            resultConstraint == other.resultConstraint &&
            streamConstraint == other.streamConstraint &&
            pageConstraint == other.pageConstraint &&
            analyticsConstraint == other.analyticsConstraint

    override fun hashCode(): Int {
        var result = mandatoryCondition.hashCode()
        result = 31 * result + fieldConstraint.hashCode()
        result = 31 * result + resultConstraint.hashCode()
        result = 31 * result + streamConstraint.hashCode()
        result = 31 * result + pageConstraint.hashCode()
        result = 31 * result + analyticsConstraint.hashCode()
        return result
    }

    internal class Builder {
        private var mandatoryCondition: NormalizedCondition = NormalizedCondition.All
        private var fieldConstraint: QueryFieldConstraint = QueryFieldConstraint.DenyAll
        private var resultConstraint: ResultPlanningConstraint = ResultPlanningConstraint.Unrestricted
        private var streamConstraint: StreamPlanningConstraint = StreamPlanningConstraint.Unrestricted
        private var pageConstraint: PagePlanningConstraint = PagePlanningConstraint.Unrestricted
        private var analyticsConstraint: AnalyticsPlanningConstraint = AnalyticsPlanningConstraint.Unrestricted

        fun mandatoryCondition(condition: NormalizedCondition): Builder = apply {
            mandatoryCondition = condition
        }

        fun fieldConstraint(constraint: QueryFieldConstraint): Builder = apply {
            fieldConstraint = constraint
        }

        fun resultConstraint(constraint: ResultPlanningConstraint): Builder = apply {
            resultConstraint = constraint
        }

        fun streamConstraint(constraint: StreamPlanningConstraint): Builder = apply {
            streamConstraint = constraint
        }

        fun pageConstraint(constraint: PagePlanningConstraint): Builder = apply {
            pageConstraint = constraint
        }

        fun analyticsConstraint(constraint: AnalyticsPlanningConstraint): Builder = apply {
            analyticsConstraint = constraint
        }

        fun build(): QueryPolicyAllowance {
            mandatoryCondition.findNativePath(POLICY_PATH.property("mandatoryCondition"))?.let { path ->
                throw QueryPolicyConstraintException(path, QueryRejectionCode.MANDATORY_NATIVE_NOT_ALLOWED)
            }
            return QueryPolicyAllowance(
                mandatoryCondition,
                fieldConstraint,
                resultConstraint,
                streamConstraint,
                pageConstraint,
                analyticsConstraint,
            )
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

internal class QueryPolicyEnforcer(
    private val policy: QueryPolicy,
) {
    fun authorize(input: QueryPolicyInput): Mono<PlanningConstraints> = Mono.defer {
        validateTarget(input)
        evaluatePolicy(input)
    }.flatMap { decision ->
        when (decision) {
            is QueryPolicyDecision.Allow -> {
                validateAllowance(decision.allowance, input.schema)
                Mono.just(decision.allowance.toPlanningConstraints(input.executionContext.validationMode))
            }

            is QueryPolicyDecision.Deny -> Mono.error(
                rejectedException(
                    QueryRejectionCategory.ACCESS_DENIED,
                    POLICY_PATH,
                    QueryRejectionCode.POLICY_DENIED,
                    QueryPolicyDeniedException(decision.reason),
                ),
            )
        }
    }

    private fun evaluatePolicy(input: QueryPolicyInput): Mono<QueryPolicyDecision> =
        Mono.defer { policy.decide(input) }
            .onErrorMap { error ->
                when (error) {
                    is QueryPolicyConstraintException -> rejectedException(
                        QueryRejectionCategory.ACCESS_DENIED,
                        error.path,
                        error.code,
                        error,
                    )

                    else -> rejectedException(
                        QueryRejectionCategory.ACCESS_DENIED,
                        POLICY_PATH,
                        QueryRejectionCode.POLICY_EVALUATION_FAILED,
                        error,
                    )
                }
            }
            .switchIfEmpty(
                Mono.error(
                    rejectedException(
                        QueryRejectionCategory.ACCESS_DENIED,
                        POLICY_PATH,
                        QueryRejectionCode.POLICY_DECISION_MISSING,
                    ),
                ),
            )

    private fun validateTarget(input: QueryPolicyInput) {
        if (input.executionContext.target != input.invocation.target || input.schema.target != input.invocation.target) {
            throw rejectedException(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT.property("target"),
                QueryRejectionCode.TARGET_SCHEMA_MISMATCH,
            )
        }
    }

    private fun validateAllowance(
        allowance: QueryPolicyAllowance,
        schema: QueryDocumentSchema,
    ) {
        val fieldConstraints = listOf(
            "filterFields" to allowance.fieldConstraint.filterFields,
            "projectionFields" to allowance.fieldConstraint.projectionFields,
            "sortFields" to allowance.fieldConstraint.sortFields,
            "analyticsDimensionFields" to allowance.fieldConstraint.analyticsDimensionFields,
            "analyticsMetricFields" to allowance.fieldConstraint.analyticsMetricFields,
        )
        fieldConstraints.forEach { (name, access) ->
            val invalid = (access as? FieldAccess.AllowList)?.fields?.firstOrNull { it !in schema.fields }
            if (invalid != null) {
                rejectInvalidConstraint(name)
            }
        }
        val invalidScope = (allowance.fieldConstraint.searchScopes as? SearchScopeAccess.AllowList)
            ?.scopes?.firstOrNull { it !in schema.searchScopes }
        if (invalidScope != null) {
            rejectInvalidConstraint("searchScopes")
        }
    }

    private fun rejectInvalidConstraint(name: String): Nothing =
        throw rejectedException(
            QueryRejectionCategory.ACCESS_DENIED,
            POLICY_PATH.property("fieldConstraint").property(name),
            QueryRejectionCode.POLICY_CONSTRAINT_INVALID,
        )
}

internal class QueryPolicyConstraintException(
    val path: QueryRejectionPath,
    val code: QueryRejectionCode,
) : IllegalArgumentException("Invalid query policy constraint: $code at $path")

internal class QueryPolicyDeniedException(
    val reason: QueryPolicyDenial,
) : IllegalStateException("Query policy denied the request.")

private fun NormalizedCondition.findNativePath(path: QueryRejectionPath): QueryRejectionPath? =
    when (this) {
        NormalizedCondition.All,
        NormalizedCondition.None,
        is NormalizedCondition.Predicate,
        is NormalizedCondition.Search,
        -> null

        is NormalizedCondition.Native -> path
        is NormalizedCondition.ElementMatch -> condition.findNativePath(path.property("condition"))
        is NormalizedCondition.Junction -> children.mapIndexedNotNull { index, child ->
            child.findNativePath(path.property("children").index(index))
        }.firstOrNull()
    }

private val POLICY_PATH = QueryRejectionPath.ROOT.property("policy")
