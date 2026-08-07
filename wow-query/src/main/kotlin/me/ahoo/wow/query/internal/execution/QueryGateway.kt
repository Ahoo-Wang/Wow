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

import me.ahoo.wow.query.internal.admission.RawAdmissionGuard
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.QueryNormalizer
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.planning.ResultPlanningConstraint
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.policy.QueryExecutionContextFactory
import me.ahoo.wow.query.internal.policy.QueryExecutionRequest
import me.ahoo.wow.query.internal.policy.QueryPolicyEnforcer
import me.ahoo.wow.query.internal.policy.QueryPolicyInput
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.QuerySchemaRegistry
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal fun interface QueryInvocationFactory {
    fun create(): QueryInvocation
}

internal class QueryGateway(
    private val admissionGuard: RawAdmissionGuard,
    private val normalizer: QueryNormalizer,
    private val schemaRegistry: QuerySchemaRegistry,
    private val contextFactory: QueryExecutionContextFactory,
    private val policyEnforcer: QueryPolicyEnforcer,
    private val planner: QueryPlanner,
    private val routeResolver: QueryExecutionRouteResolver,
    private val executor: QueryExecutor,
    private val deadlineEnforcer: QueryDeadlineEnforcer,
    private val errorBoundary: QueryErrorBoundary = QueryErrorBoundary(),
    private val lifecycleMonitor: QueryLifecycleMonitor = QueryLifecycleMonitor(),
) {
    fun single(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Mono<BackendRecord> = singleResult(request, invocationFactory) { record -> record }

    fun <T : Any> singleResult(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
        materialize: (BackendRecord) -> T,
    ): Mono<T> = observeMono(request, QueryOperation.SINGLE) {
        prepare(request, QueryOperation.SINGLE, invocationFactory)
            .flatMap { prepared -> executor.single(prepared.route, prepared.options) }
            .map(materialize)
    }

    fun stream(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Flux<BackendRecord> = streamResult(request, invocationFactory) { record -> record }

    fun <T : Any> streamResult(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
        materialize: (BackendRecord) -> T,
    ): Flux<T> = observeFlux(request, QueryOperation.STREAM) {
        prepare(request, QueryOperation.STREAM, invocationFactory)
            .flatMapMany { prepared -> executor.stream(prepared.route, prepared.options) }
            .map(materialize)
    }

    fun page(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Mono<BackendPage> = pageResult(request, invocationFactory) { page -> page }

    fun <T : Any> pageResult(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
        materialize: (BackendPage) -> T,
    ): Mono<T> = observeMono(request, QueryOperation.PAGE) {
        prepare(request, QueryOperation.PAGE, invocationFactory)
            .flatMap { prepared -> executor.page(prepared.route, prepared.options) }
            .map(materialize)
    }

    fun count(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Mono<Long> = observeMono(request, QueryOperation.COUNT) {
        prepare(request, QueryOperation.COUNT, invocationFactory)
            .flatMap { prepared -> executor.count(prepared.route, prepared.options) }
    }

    fun analyze(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Mono<BackendAnalyticsPage> = observeMono(request, QueryOperation.ANALYZE) {
        prepare(request, QueryOperation.ANALYZE, invocationFactory)
            .flatMap { prepared -> executor.analyze(prepared.route, prepared.options) }
    }

    private fun prepare(
        request: QueryExecutionRequest,
        expectedOperation: QueryOperation,
        invocationFactory: QueryInvocationFactory,
    ): Mono<PreparedExecution> = Mono.defer {
        val normalized = freezeAndNormalize(request, expectedOperation, invocationFactory)
        contextFactory.resolve(request).flatMap { context ->
            val schema = schemaRegistry[normalized.target]
                ?: rejectQuery(
                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                    QueryRejectionPath.ROOT.property("target"),
                    QueryRejectionCode.SCHEMA_NOT_REGISTERED,
                )
            policyEnforcer.authorize(QueryPolicyInput(context, normalized, schema)).map { constraints ->
                val decision = planner.plan(normalized, schema, constraints.constrain(context.budget))
                PreparedExecution(
                    routeResolver.resolve(context, normalized, schema, decision),
                    QueryExecutionOptions.from(context),
                )
            }
        }
    }

    private fun freezeAndNormalize(
        request: QueryExecutionRequest,
        expectedOperation: QueryOperation,
        invocationFactory: QueryInvocationFactory,
    ): NormalizedQueryInvocation {
        val invocation = invocationFactory.create()
        if (invocation.target != request.target || invocation.operation != expectedOperation) {
            rejectQuery(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT.property("input"),
                QueryRejectionCode.INVALID_INVOCATION,
            )
        }
        return normalizer.normalize(admissionGuard.admit(invocation))
    }

    private fun <T : Any> observeMono(
        request: QueryExecutionRequest,
        operation: QueryOperation,
        source: () -> Mono<T>,
    ): Mono<T> {
        val normalized = deadlineEnforcer.enforceMono(request.deadline, source)
            .onErrorMap(errorBoundary::normalize)
        return lifecycleMonitor.observeMono(QueryLifecycleDescriptor(request, operation), normalized)
    }

    private fun <T : Any> observeFlux(
        request: QueryExecutionRequest,
        operation: QueryOperation,
        source: () -> Flux<T>,
    ): Flux<T> {
        val normalized = deadlineEnforcer.enforceFlux(request.deadline, source)
            .onErrorMap(errorBoundary::normalize)
        return lifecycleMonitor.observeFlux(QueryLifecycleDescriptor(request, operation), normalized)
    }

    private data class PreparedExecution(
        val route: QueryExecutionRoute,
        val options: QueryExecutionOptions,
    )
}

private fun PlanningConstraints.constrain(budget: QueryExecutionBudget): PlanningConstraints {
    if (budget.hasUnsupportedConstraints()) {
        rejectQuery(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionPath.ROOT.property("executionContext").property("budget"),
            QueryRejectionCode.EXECUTION_BUDGET_UNSUPPORTED,
        )
    }
    val requestedMaximum = budget.maxReturnedRecords ?: return this
    val policyMaximum = (resultConstraint as? ResultPlanningConstraint.MaximumRecords)?.value
    val effectiveMaximum = policyMaximum?.coerceAtMost(requestedMaximum) ?: requestedMaximum
    return copy(resultConstraint = ResultPlanningConstraint.MaximumRecords(effectiveMaximum))
}

private fun QueryExecutionBudget.hasUnsupportedConstraints(): Boolean =
    sequenceOf(maxScannedRecords, maxCandidateBuckets, maxReturnedBuckets, maxCursorPages).any { it != null } ||
        allowDiskUse
