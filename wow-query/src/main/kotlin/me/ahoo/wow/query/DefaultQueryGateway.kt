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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.DynamicDocument
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
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.expression.InvocationExpressionNormalizer
import me.ahoo.wow.query.invocation.QueryDeadlineExceededException
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.invocation.QueryInvocationFactory
import me.ahoo.wow.query.invocation.QueryInvocationSeed
import me.ahoo.wow.query.metrics.QueryGatewayMetricState
import me.ahoo.wow.query.metrics.QueryGatewayMetrics
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.DefaultQueryPlanner
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.plan.QueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.result.DefaultResultPolicyChain
import me.ahoo.wow.query.result.ResultPolicyContext
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.validation.QueryRequestSchemaValidator
import me.ahoo.wow.query.validation.QueryRequestValidator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultQueryGateway private constructor(
    private val invocationFactory: QueryInvocationFactory,
    private val requestValidator: QueryRequestValidator,
    private val schemaValidator: QueryRequestSchemaValidator,
    private val schemaResolver: QuerySchemaResolver,
    private val policyChain: DefaultQueryPolicyChain,
    private val backendResolver: QueryBackendResolver,
    private val planner: DefaultQueryPlanner,
    private val resultPolicyChain: DefaultResultPolicyChain,
    private val metrics: QueryGatewayMetrics,
    private val stageObserver: QueryGatewayStageObserver,
    private val enabledCapabilities: Set<QueryCapabilityId>
) : QueryGateway {
    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.defer {
        val metricState = metrics.state(request, QueryOperation.SINGLE)
        metrics.observe(executeSingle(request, metricState), metricState)
    }

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.defer {
        val metricState = metrics.state(request, QueryOperation.LIST)
        metrics.observe(executeList(request, metricState), metricState)
    }

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.defer {
        val metricState = metrics.state(request, QueryOperation.PAGE)
        metrics.observe(executePage(request, metricState), metricState)
    }

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.defer {
        val metricState = metrics.state(request, QueryOperation.COUNT)
        metrics.observe(executeCount(request, metricState), metricState)
    }

    private fun <R : Any> executeSingle(
        request: SingleQueryRequest<R>,
        metricState: QueryGatewayMetricState
    ): Mono<R> = prepare(request, QueryOperation.SINGLE, metricState).flatMap { prepared ->
        @Suppress("UNCHECKED_CAST")
        val plan = prepared.plan as SingleQueryPlanV1<R>
        stageObserver.record(QueryGatewayStage.EXECUTE)
        val execution = zeroOrOne(Mono.defer { prepared.resolvedBackend.backend.single(plan) })
        prepared.invocation.deadlineGuard.enforce(
            execution,
            plan.effectiveDeadline,
            QueryStage.EXECUTION
        ).onErrorMap(::mapExecutionError)
            .flatMap { value -> applyResultPolicy(prepared, value) }
    }

    private fun <R : Any> executeList(
        request: ListQueryRequest<R>,
        metricState: QueryGatewayMetricState
    ): Flux<R> = prepare(request, QueryOperation.LIST, metricState).flatMapMany { prepared ->
        @Suppress("UNCHECKED_CAST")
        val plan = prepared.plan as ListQueryPlanV1<R>
        val emitted = AtomicBoolean()
        stageObserver.record(QueryGatewayStage.EXECUTE)
        val execution = Flux.defer { prepared.resolvedBackend.backend.list(plan) }
        prepared.invocation.deadlineGuard.enforce(
            execution,
            plan.effectiveDeadline,
            QueryStage.EXECUTION
        ).onErrorMap(::mapExecutionError)
            .concatMap({ value -> applyResultPolicy(prepared, value) }, 1)
            .doOnNext { emitted.set(true) }
            .onErrorMap { error ->
                if (emitted.get() && !error.isIncomplete()) incompleteResult(error) else error
            }
    }

    private fun <R : Any> executePage(
        request: PageQueryRequest<R>,
        metricState: QueryGatewayMetricState
    ): Mono<QueryPage<R>> = prepare(request, QueryOperation.PAGE, metricState).flatMap { prepared ->
        @Suppress("UNCHECKED_CAST")
        val plan = prepared.plan as PageQueryPlanV1<R>
        stageObserver.record(QueryGatewayStage.EXECUTE)
        val execution = atomic(Mono.defer { prepared.resolvedBackend.backend.page(plan) })
        prepared.invocation.deadlineGuard.enforce(
            execution,
            plan.effectiveDeadline,
            QueryStage.EXECUTION
        ).onErrorMap(::mapExecutionError)
            .flatMap { page ->
                recordResultPolicyStage(prepared)
                Flux.fromIterable(page.items)
                    .concatMap({ value -> applyResultPolicy(prepared, value, recordStage = false) }, 1)
                    .collectList()
                    .map { items -> QueryPage(items, page.total, page.consistency) }
            }
    }

    private fun executeCount(
        request: CountQueryRequest,
        metricState: QueryGatewayMetricState
    ): Mono<Long> = prepare(request, QueryOperation.COUNT, metricState).flatMap { prepared ->
        val plan = prepared.plan as CountQueryPlanV1
        stageObserver.record(QueryGatewayStage.EXECUTE)
        val execution = atomic(Mono.defer { prepared.resolvedBackend.backend.count(plan) })
        prepared.invocation.deadlineGuard.enforce(
            execution,
            plan.effectiveDeadline,
            QueryStage.EXECUTION
        ).onErrorMap(::mapExecutionError)
            .flatMap { value -> applyResultPolicy(prepared, value) }
    }

    private fun prepare(
        request: QueryRequest,
        operation: QueryOperation,
        metricState: QueryGatewayMetricState
    ): Mono<PreparedQuery> = Mono.defer {
        stageObserver.record(QueryGatewayStage.ADMISSION)
        invocationFactory.admit(request, operation)
    }.onErrorMap { error -> mapAdmissionError(error) }
        .map { seed ->
            stageObserver.record(QueryGatewayStage.STRUCTURE_VALIDATION)
            requestValidator.validateStructure(seed.request)
            seed
        }.flatMap(::resolveSchemaAndValidate)
        .flatMap { invocation -> evaluatePolicy(invocation, metricState) }
        .map(::validateConfiguredCapabilities)
        .flatMap { evaluated -> resolveBackend(evaluated, metricState) }
        .flatMap { evaluated -> createPlan(evaluated, operation) }

    private fun validateConfiguredCapabilities(evaluated: InvocationPolicy): InvocationPolicy {
        if (!enabledCapabilities.containsAll(requestedCapabilities(evaluated.policyResult.securedExpression))) {
            throw unsupportedCapability()
        }
        return evaluated
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
        return capabilities
    }

    private fun resolveSchemaAndValidate(seed: QueryInvocationSeed): Mono<QueryInvocation> {
        val schemaResolution = Mono.defer {
            stageObserver.record(QueryGatewayStage.SCHEMA_RESOLVE)
            schemaResolver.resolve(seed.request.target)
        }.switchIfEmpty(Mono.error(invalidQuery()))
        return seed.deadlineGuard.enforce(schemaResolution, seed.admissionDeadline, QueryStage.VALIDATION)
            .onErrorMap { error -> mapPreparationError(error, QueryStage.VALIDATION) }
            .flatMap { schema ->
                val normalization = Mono.fromCallable {
                    stageObserver.record(QueryGatewayStage.NORMALIZE)
                    seed.toInvocation(schema) { expression, frozenInstant, zoneId ->
                        InvocationExpressionNormalizer.normalize(expression, frozenInstant, zoneId)
                    }
                }
                seed.deadlineGuard.enforce(normalization, seed.admissionDeadline, QueryStage.NORMALIZE)
            }.onErrorMap { error -> mapPreparationError(error, QueryStage.NORMALIZE) }
            .flatMap { invocation ->
                val validation = Mono.fromCallable {
                    stageObserver.record(QueryGatewayStage.SCHEMA_VALIDATION)
                    schemaValidator.validate(
                        invocation.request,
                        invocation.normalizedExpression,
                        invocation.schema
                    )
                    invocation
                }
                invocation.deadlineGuard.enforce(
                    validation,
                    invocation.admissionDeadline,
                    QueryStage.VALIDATION
                )
            }.onErrorMap { error -> mapPreparationError(error, QueryStage.VALIDATION) }
    }

    private fun evaluatePolicy(
        invocation: QueryInvocation,
        metricState: QueryGatewayMetricState
    ): Mono<InvocationPolicy> {
        stageObserver.record(QueryGatewayStage.POLICY)
        return policyChain.evaluate(invocation, metricState::recordPolicyDescriptor).map { policyResult ->
            stageObserver.record(QueryGatewayStage.POLICY_OUTPUT_VALIDATION)
            InvocationPolicy(invocation, policyResult)
        }
    }

    private fun resolveBackend(
        evaluated: InvocationPolicy,
        metricState: QueryGatewayMetricState
    ): Mono<EvaluatedBackend> {
        val resolution = Mono.defer {
            stageObserver.record(QueryGatewayStage.BACKEND_RESOLVE)
            backendResolver.resolve(
                QueryBackendResolutionContext(
                    target = evaluated.invocation.request.target,
                    schema = evaluated.invocation.schema,
                    securedExpression = evaluated.policyResult.securedExpression
                )
            )
        }.switchIfEmpty(Mono.error(backendNotReady()))
        return evaluated.invocation.deadlineGuard.enforce(
            resolution,
            evaluated.invocation.admissionDeadline,
            QueryStage.BACKEND_RESOLUTION
        ).onErrorMap(::mapBackendResolutionError)
            .map { resolved ->
                metricState.backendId.set(resolved.descriptor.backendId)
                EvaluatedBackend(evaluated, resolved)
            }
    }

    private fun createPlan(
        evaluated: EvaluatedBackend,
        operation: QueryOperation
    ): Mono<PreparedQuery> {
        stageObserver.record(QueryGatewayStage.PLAN)
        return planner.plan(
            evaluated.evaluated.invocation,
            evaluated.evaluated.policyResult,
            evaluated.resolvedBackend
        ).onErrorMap(::mapPlanningError)
            .map { plan ->
                PreparedQuery(
                    invocation = evaluated.evaluated.invocation,
                    resolvedBackend = evaluated.resolvedBackend,
                    plan = plan,
                    resultPolicyContext = ResultPolicyContext(
                        target = plan.target,
                        operation = operation,
                        resultShape = plan.authorizedResultShape,
                        invocationScope = evaluated.evaluated.invocation.scope,
                        frozenInstant = evaluated.evaluated.invocation.frozenInstant,
                        zoneId = evaluated.evaluated.invocation.zoneId,
                        backendId = evaluated.resolvedBackend.descriptor.backendId
                    )
                )
            }
    }

    private fun <R : Any> applyResultPolicy(
        prepared: PreparedQuery,
        value: R,
        recordStage: Boolean = true
    ): Mono<R> = Mono.defer {
        if (recordStage) {
            recordResultPolicyStage(prepared)
        }
        validateResult(prepared.plan.authorizedResultShape, value)
        resultPolicyChain.apply(prepared.resultPolicyContext, value)
            .map { result ->
                validateResult(prepared.plan.authorizedResultShape, result)
                @Suppress("UNCHECKED_CAST")
                result as R
            }
    }.let { result ->
        prepared.invocation.deadlineGuard.enforce(
            result,
            prepared.plan.effectiveDeadline,
            QueryStage.RESULT_POLICY
        )
    }.onErrorMap { error -> mapResultPolicyError(error) }

    private fun recordResultPolicyStage(prepared: PreparedQuery) {
        if (prepared.resultPolicyStarted.compareAndSet(false, true)) {
            stageObserver.record(QueryGatewayStage.RESULT_POLICY)
        }
    }

    private fun <T : Any> atomic(publisher: Mono<T>): Mono<T> = publisher.flux().collectList().flatMap { values ->
        if (values.size == 1) Mono.just(values.single()) else Mono.error(resultInvalid())
    }

    private fun <T : Any> zeroOrOne(publisher: Mono<T>): Mono<T> = publisher.flux().collectList().flatMap { values ->
        when (values.size) {
            0 -> Mono.empty()
            1 -> Mono.just(values.single())
            else -> Mono.error(resultInvalid())
        }
    }

    private fun validateResult(shape: QueryPlanResultShape, value: Any) {
        val valid = when (shape) {
            is QueryPlanResultShape.Typed -> shape.resultType.isInstance(value)
            is QueryPlanResultShape.Dynamic -> value is DynamicDocument
            QueryPlanResultShape.Count -> value is Long && value >= 0
        }
        if (!valid) {
            throw resultInvalid()
        }
    }

    private fun mapPreparationError(error: Throwable, stage: QueryStage): Throwable = when (error) {
        is QueryException -> error
        is QueryDeadlineExceededException -> deadlineExceeded(error.stage)
        else -> QueryException(QueryErrorCode.INVALID_QUERY, stage, QueryErrorReason.INVALID_REQUEST)
    }

    private fun mapAdmissionError(error: Throwable): Throwable = when (error) {
        is QueryException -> error
        else -> QueryException(
            QueryErrorCode.POLICY_FAILURE,
            QueryStage.ADMISSION,
            QueryErrorReason.POLICY_EVALUATION_FAILED
        )
    }

    private fun mapPlanningError(error: Throwable): Throwable = when (error) {
        is QueryException -> error
        is QueryDeadlineExceededException -> deadlineExceeded(error.stage)
        else -> QueryException(QueryErrorCode.INVALID_QUERY, QueryStage.PLANNING, QueryErrorReason.INVALID_REQUEST)
    }

    private fun mapBackendResolutionError(error: Throwable): Throwable = when (error) {
        is QueryException -> error
        is QueryDeadlineExceededException -> deadlineExceeded(error.stage)
        else -> backendNotReady()
    }

    private fun mapExecutionError(error: Throwable): Throwable = when (error) {
        is QueryException -> error
        is QueryDeadlineExceededException -> deadlineExceeded(error.stage)
        else -> QueryException(
            QueryErrorCode.BACKEND_FAILURE,
            QueryStage.EXECUTION,
            QueryErrorReason.BACKEND_EXECUTION_FAILED
        )
    }

    private fun mapResultPolicyError(error: Throwable): Throwable = when (error) {
        is QueryException -> error
        is QueryDeadlineExceededException -> deadlineExceeded(error.stage)
        else -> resultInvalid()
    }

    private fun invalidQuery(): QueryException = QueryException(
        QueryErrorCode.INVALID_QUERY,
        QueryStage.VALIDATION,
        QueryErrorReason.INVALID_REQUEST
    )

    private fun backendNotReady(): QueryException = QueryException(
        QueryErrorCode.BACKEND_NOT_READY,
        QueryStage.BACKEND_RESOLUTION,
        QueryErrorReason.BACKEND_UNAVAILABLE
    )

    private fun unsupportedCapability(): QueryException = QueryException(
        QueryErrorCode.UNSUPPORTED_CAPABILITY,
        QueryStage.PLANNING,
        QueryErrorReason.CAPABILITY_DENIED
    )

    private fun resultInvalid(): QueryException = QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.RESULT_POLICY,
        QueryErrorReason.RESULT_INVALID
    )

    private fun deadlineExceeded(stage: QueryStage): QueryException = QueryException(
        QueryErrorCode.DEADLINE_EXCEEDED,
        stage,
        QueryErrorReason.DEADLINE_REACHED
    )

    private fun incompleteResult(error: Throwable): QueryException = QueryException(
        QueryErrorCode.INCOMPLETE_RESULT,
        QueryStage.EXECUTION,
        QueryErrorReason.INCOMPLETE_STREAM,
        (error as? QueryException)?.code ?: QueryErrorCode.BACKEND_FAILURE
    )

    private fun Throwable.isIncomplete(): Boolean =
        this is QueryException && code == QueryErrorCode.INCOMPLETE_RESULT

    private class InvocationPolicy(
        val invocation: QueryInvocation,
        val policyResult: me.ahoo.wow.query.policy.CombinedQueryPolicyResult
    )

    private class EvaluatedBackend(
        val evaluated: InvocationPolicy,
        val resolvedBackend: ResolvedQueryBackend
    )

    private class PreparedQuery(
        val invocation: QueryInvocation,
        val resolvedBackend: ResolvedQueryBackend,
        val plan: QueryPlanV1,
        val resultPolicyContext: ResultPolicyContext,
        val resultPolicyStarted: AtomicBoolean = AtomicBoolean()
    )

    internal companion object {
        @JvmSynthetic
        internal fun create(
            invocationFactory: QueryInvocationFactory,
            requestValidator: QueryRequestValidator,
            schemaValidator: QueryRequestSchemaValidator,
            schemaResolver: QuerySchemaResolver,
            policyChain: DefaultQueryPolicyChain,
            backendResolver: QueryBackendResolver,
            planner: DefaultQueryPlanner,
            resultPolicyChain: DefaultResultPolicyChain,
            metrics: QueryGatewayMetrics,
            stageObserver: QueryGatewayStageObserver,
            enabledCapabilities: Set<QueryCapabilityId>
        ): DefaultQueryGateway = DefaultQueryGateway(
            invocationFactory,
            requestValidator,
            schemaValidator,
            schemaResolver,
            policyChain,
            backendResolver,
            planner,
            resultPolicyChain,
            metrics,
            stageObserver,
            enabledCapabilities
        )
    }
}
