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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.ResultQueryRequest
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.invocation.QueryDeadline
import me.ahoo.wow.query.invocation.QueryDeadlineExceededException
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryExpressionValidator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections

private const val SYSTEM_POLICY_ID: String = "system"
internal const val COMBINED_POLICY_ID: String = "combined"

internal class DefaultQueryPolicyChain(
    systemPolicy: SystemQueryPolicy,
    customPolicies: List<QueryPolicyDescriptor>,
    private val expressionValidator: QueryExpressionValidator
) {
    private val policies: List<QueryPolicyDescriptor>

    init {
        require(customPolicies.none { it.policy is SystemQueryPolicy }) {
            "System query policy cannot be registered as a custom policy."
        }
        val orderedCustomPolicies = customPolicies.sortedWith(PolicyDescriptorComparator)
        policies = Collections.unmodifiableList(
            listOf(QueryPolicyDescriptor(SYSTEM_POLICY_ID, Int.MIN_VALUE, systemPolicy)) + orderedCustomPolicies
        )
    }

    fun evaluate(
        invocation: QueryInvocation,
        descriptorObserver: (String) -> Unit = {}
    ): Mono<CombinedQueryPolicyResult> = evaluate(
        context = contextOf(invocation),
        admissionDeadline = invocation.admissionDeadline,
        deadlineGuard = invocation.deadlineGuard,
        descriptorObserver = descriptorObserver
    )

    fun evaluate(
        context: QueryPolicyContext,
        deadlineGuard: QueryDeadlineGuard,
        admissionDeadline: Instant? = QueryDeadline.from(
            context.frozenInstant,
            context.requestBudget.timeout,
            QueryStage.POLICY
        ),
        descriptorObserver: (String) -> Unit = {}
    ): Mono<CombinedQueryPolicyResult> = Mono.defer {
        val evaluation = Flux.fromIterable(policies)
            .concatMap { descriptor -> evaluate(descriptor, context, descriptorObserver) }
            .collectList()
            .map { results ->
                descriptorObserver(COMBINED_POLICY_ID)
                combine(context, results)
            }
        deadlineGuard.enforce(evaluation, admissionDeadline, QueryStage.POLICY)
    }.onErrorMap { error -> mapPolicyError(error) }

    private fun evaluate(
        descriptor: QueryPolicyDescriptor,
        context: QueryPolicyContext,
        descriptorObserver: (String) -> Unit
    ): Mono<QueryPolicyResult> = Mono.defer {
        descriptorObserver(descriptor.id)
        descriptor.policy.evaluate(context)
    }
        .switchIfEmpty(Mono.error(policyFailure()))
        .map { result -> validateResult(result, context) }

    private fun validateResult(
        result: QueryPolicyResult,
        context: QueryPolicyContext
    ): QueryPolicyResult {
        expressionValidator.validateStructure(result.mandatoryExpression)
        expressionValidator.validateSchema(result.mandatoryExpression, context.schema)
        when (val access = result.constraints.fieldAccess) {
            QueryFieldAccess.Unrestricted -> Unit
            is QueryFieldAccess.Restricted -> require(context.schema.fields.keys.containsAll(access.fields))
        }
        val schemaCapabilities = context.schema.fields.values.flatMap { it.capabilities }.toSet()
        require(schemaCapabilities.containsAll(result.constraints.capabilityAccess.keys))
        return result
    }

    private fun combine(
        context: QueryPolicyContext,
        results: List<QueryPolicyResult>
    ): CombinedQueryPolicyResult {
        val constraints = combineConstraints(results.map(QueryPolicyResult::constraints))
        requestedCapabilities(context.normalizedExpression).forEach { capability ->
            if (constraints.capabilityAccess[capability] != CapabilityDecision.GRANT) {
                throw CapabilityDeniedException()
            }
        }
        val mandatoryExpression = ExpressionNormalizer.logical(
            me.ahoo.wow.api.query.expression.LogicalOperator.AND,
            results.map(QueryPolicyResult::mandatoryExpression)
        ) as PortableExpression
        val securedExpression = ExpressionNormalizer.logical(
            me.ahoo.wow.api.query.expression.LogicalOperator.AND,
            listOf(context.normalizedExpression, mandatoryExpression)
        )
        expressionValidator.validateStructure(securedExpression)
        expressionValidator.validateSchema(securedExpression, context.schema)
        return CombinedQueryPolicyResult(securedExpression, mandatoryExpression, constraints)
    }

    private fun combineConstraints(constraints: List<QueryPolicyConstraints>): QueryPolicyConstraints {
        val fieldAccess = constraints.fold(QueryFieldAccess.UNRESTRICTED, ::intersect)
        val maxBudget = constraints.fold(QueryBudgetLimit.UNBOUNDED) { current, constraint ->
            QueryBudgetLimit.min(null, current, constraint.maxBudget, QueryBudgetLimit.UNBOUNDED)
        }
        val decisions = LinkedHashMap<QueryCapabilityId, MutableList<CapabilityDecision>>()
        constraints.forEach { constraint ->
            constraint.capabilityAccess.forEach { (capability, decision) ->
                decisions.computeIfAbsent(capability) { mutableListOf() } += decision
            }
        }
        val capabilityAccess = decisions.entries.sortedWith(CapabilityEntryComparator)
            .associateTo(LinkedHashMap()) { entry ->
                entry.key to decide(entry.value)
            }
        return QueryPolicyConstraints(fieldAccess, capabilityAccess, maxBudget)
    }

    private fun intersect(
        current: QueryFieldAccess,
        next: QueryPolicyConstraints
    ): QueryFieldAccess = when {
        current === QueryFieldAccess.Unrestricted -> next.fieldAccess
        next.fieldAccess === QueryFieldAccess.Unrestricted -> current
        current is QueryFieldAccess.Restricted && next.fieldAccess is QueryFieldAccess.Restricted ->
            QueryFieldAccess.Restricted(current.fields.intersect(next.fieldAccess.fields))

        else -> error("Unknown query field access type.")
    }

    private fun decide(decisions: List<CapabilityDecision>): CapabilityDecision = when {
        CapabilityDecision.DENY in decisions -> CapabilityDecision.DENY
        CapabilityDecision.GRANT in decisions -> CapabilityDecision.GRANT
        else -> CapabilityDecision.ABSTAIN
    }

    private fun requestedCapabilities(expression: QueryExpression): Set<QueryCapabilityId> {
        val capabilities = LinkedHashSet<QueryCapabilityId>()
        val pending = ArrayDeque<QueryExpression>()
        pending += expression
        while (pending.isNotEmpty()) {
            when (val current = pending.removeLast()) {
                is FullTextExpression -> capabilities += current.capabilityId
                is NativeExpression -> capabilities += current.capabilityId
                is LogicalExpression -> current.operands.forEach(pending::addLast)
                is PortableLogicalExpression -> current.operands.forEach(pending::addLast)
                is ElementMatchExpression -> pending += current.predicate
                MatchAll,
                MatchNone,
                is PredicateExpression -> Unit
                is RelativeTimeExpression -> throw IllegalStateException("Relative time was not normalized.")
            }
        }
        return Collections.unmodifiableSet(capabilities)
    }

    private fun contextOf(invocation: QueryInvocation): QueryPolicyContext = QueryPolicyContext(
        target = invocation.request.target,
        operation = invocation.operation,
        normalizedExpression = invocation.normalizedExpression,
        resultShape = when (val request = invocation.request) {
            is CountQueryRequest -> QueryPolicyResultShape.Count
            is ResultQueryRequest<*> -> when (val resultShape = request.resultShape) {
                QueryResultShape.Dynamic -> QueryPolicyResultShape.Dynamic
                is QueryResultShape.Typed<*> -> QueryPolicyResultShape.Typed(
                    resultShape.resultType,
                    resultShape.projection
                )
            }
        },
        invocationScope = invocation.scope,
        schema = invocation.schema,
        requestBudget = invocation.request.budget,
        frozenInstant = invocation.frozenInstant,
        zoneId = invocation.zoneId
    )

    private fun mapPolicyError(error: Throwable): Throwable = when (error) {
        is QueryPolicyDeniedException -> QueryException(
            QueryErrorCode.POLICY_DENIED,
            QueryStage.POLICY,
            QueryErrorReason.POLICY_EVALUATION_FAILED
        )

        is CapabilityDeniedException -> QueryException(
            QueryErrorCode.POLICY_DENIED,
            QueryStage.POLICY,
            QueryErrorReason.CAPABILITY_DENIED
        )

        is QueryDeadlineExceededException -> QueryException(
            QueryErrorCode.DEADLINE_EXCEEDED,
            error.stage,
            QueryErrorReason.DEADLINE_REACHED
        )

        else -> policyFailure()
    }

    private fun policyFailure(): QueryException = QueryException(
        QueryErrorCode.POLICY_FAILURE,
        QueryStage.POLICY,
        QueryErrorReason.POLICY_EVALUATION_FAILED
    )

    private object PolicyDescriptorComparator : Comparator<QueryPolicyDescriptor> {
        override fun compare(first: QueryPolicyDescriptor, second: QueryPolicyDescriptor): Int {
            val orderComparison = first.order.compareTo(second.order)
            return if (orderComparison != 0) orderComparison else first.id.compareTo(second.id)
        }
    }

    private object CapabilityEntryComparator :
        Comparator<Map.Entry<QueryCapabilityId, MutableList<CapabilityDecision>>> {
        override fun compare(
            first: Map.Entry<QueryCapabilityId, MutableList<CapabilityDecision>>,
            second: Map.Entry<QueryCapabilityId, MutableList<CapabilityDecision>>
        ): Int = first.key.value.compareTo(second.key.value)
    }

    private class CapabilityDeniedException : RuntimeException(null, null, false, false)
}

internal class CombinedQueryPolicyResult(
    val securedExpression: QueryExpression,
    val mandatoryExpression: PortableExpression,
    val constraints: QueryPolicyConstraints
) {
    override fun equals(other: Any?): Boolean = other is CombinedQueryPolicyResult &&
        securedExpression == other.securedExpression && mandatoryExpression == other.mandatoryExpression &&
        constraints == other.constraints

    override fun hashCode(): Int = 31 * (31 * securedExpression.hashCode() + mandatoryExpression.hashCode()) +
        constraints.hashCode()

    override fun toString(): String =
        "CombinedQueryPolicyResult(securedExpression=<redacted>, mandatoryExpression=<redacted>, " +
            "constraints=<redacted>)"
}
