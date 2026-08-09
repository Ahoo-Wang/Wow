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
import me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.analytics.DecodedAnalyticsCursor
import me.ahoo.wow.query.internal.cursor.PersistentQueryCursorLeaseCoordinator
import me.ahoo.wow.query.internal.cursor.QueryCursorBackendState
import me.ahoo.wow.query.internal.cursor.QueryCursorBudgetCeiling
import me.ahoo.wow.query.internal.cursor.QueryCursorCleanupReason
import me.ahoo.wow.query.internal.cursor.QueryCursorEnvelope
import me.ahoo.wow.query.internal.cursor.QueryCursorLeaseBinding
import me.ahoo.wow.query.internal.cursor.QueryCursorLeaseDescriptor
import me.ahoo.wow.query.internal.cursor.QueryCursorMappingDigest
import me.ahoo.wow.query.internal.cursor.QueryCursorPosition
import me.ahoo.wow.query.internal.cursor.QueryCursorSecurityContextDigest
import me.ahoo.wow.query.internal.cursor.QueryCursorSecurityDigest
import me.ahoo.wow.query.internal.cursor.QueryCursorToken
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.QueryNormalizer
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
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
import me.ahoo.wow.query.internal.value.NonEmptyList
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import me.ahoo.wow.query.backend.AnalyticsAlias as BackendAnalyticsAlias
import me.ahoo.wow.query.backend.PlanFingerprint as BackendPlanFingerprint
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias as InternalAnalyticsAlias
import me.ahoo.wow.query.internal.plan.PlanFingerprint as InternalPlanFingerprint

internal fun interface QueryInvocationFactory {
    fun create(): QueryInvocation
}

internal data class AnalyticsExecutionPage(
    val page: BackendAnalyticsPage,
    val nextCursor: QueryCursorToken?,
)

internal data class AnalyticsCursorRuntime(
    val coordinator: PersistentQueryCursorLeaseCoordinator,
    val leaseTtl: Duration,
    val clock: Clock,
) {
    init {
        require(!leaseTtl.isZero && !leaseTtl.isNegative)
    }
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
    private val analyticsCursorRuntime: AnalyticsCursorRuntime? = null,
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

    fun analyzePublic(
        request: QueryExecutionRequest,
        invocationFactory: QueryInvocationFactory,
    ): Mono<AnalyticsExecutionPage> = observeMono(request, QueryOperation.ANALYZE) {
        preparePlanning(request, QueryOperation.ANALYZE, invocationFactory).flatMap(::executePublicAnalytics)
    }

    fun reapExpiredAnalyticsCursors(batchSize: Int): Mono<Long> {
        require(batchSize > 0) { "Query cursor reaper batch size must be positive." }
        val runtime = analyticsCursorRuntime
            ?: return Mono.error(IllegalStateException("Persistent Query cursor runtime is not configured."))
        return runtime.coordinator.reapExpired(runtime.clock.instant(), batchSize)
    }

    private fun executePublicAnalytics(prepared: PreparedPlanning): Mono<AnalyticsExecutionPage> {
        val input = prepared.normalized.input as? NormalizedQueryInput.Analytics ?: rejectInvalidInvocation()
        val plan = requireAnalyticsPlan(prepared)
        val cursorRuntime = analyticsCursorRuntime
        requireAnalyticsCursorStore(plan, cursorRuntime)
        val route = resolvePlannedRoute(prepared, plan)
        val registration = route.registry.resolve(plan)
        requireSnapshotCursorLifecycle(plan, registration, cursorRuntime)
        val mappingDigest = QueryCursorMappingDigest(registration.descriptor.mappingGenerationDigest)
        val securityDigest = QueryCursorSecurityDigest.compute(prepared.context)
        val token = input.query.cursorToken
        return if (token == null) {
            withCursorSession(
                CursorExecutionSession(
                    cursorRuntime?.coordinator,
                    null,
                    QueryCursorLeaseDescriptor(
                        plan.target,
                        registration.descriptor.key.backendId,
                        mappingDigest,
                    ),
                ),
            ) { session ->
                executeAnalyticsPage(
                    prepared,
                    route,
                    plan,
                    mappingDigest,
                    securityDigest,
                    currentPage = 1,
                    session,
                )
            }
        } else {
            continuePublicAnalytics(
                prepared,
                plan,
                mappingDigest,
                securityDigest,
                registration,
                requireNotNull(cursorRuntime),
                token,
                input.query.bucketWindow.limit,
            )
        }
    }

    private fun requireAnalyticsCursorStore(
        plan: AnalyticsQueryPlan,
        cursorRuntime: AnalyticsCursorRuntime?,
    ) {
        if (plan.grouping is PlannedAnalyticsGrouping.By && cursorRuntime == null) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                CURSOR_PATH,
                QueryRejectionCode.CURSOR_STORE_REQUIRED,
            )
        }
    }

    private fun requireSnapshotCursorLifecycle(
        plan: AnalyticsQueryPlan,
        registration: QueryBackendRegistration,
        cursorRuntime: AnalyticsCursorRuntime?,
    ) {
        val requiresLifecycle =
            plan.requiredConsistency == AnalyticsConsistency.SNAPSHOT && plan.grouping is PlannedAnalyticsGrouping.By
        val supportsLifecycle = cursorRuntime?.coordinator
            ?.supports(plan.target, registration.descriptor.key.backendId) == true
        if (requiresLifecycle && !supportsLifecycle) {
            rejectQuery(
                QueryRejectionCategory.BACKEND_UNAVAILABLE,
                QueryRejectionPath.ROOT.property("backend"),
                QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED,
            )
        }
    }

    private fun continuePublicAnalytics(
        prepared: PreparedPlanning,
        basePlan: AnalyticsQueryPlan,
        mappingDigest: QueryCursorMappingDigest,
        securityDigest: QueryCursorSecurityContextDigest,
        registration: QueryBackendRegistration,
        cursorRuntime: AnalyticsCursorRuntime,
        token: QueryCursorToken,
        limit: Int,
    ): Mono<AnalyticsExecutionPage> = cursorRuntime.coordinator.load(token).flatMap { loaded ->
        val envelope = loaded.envelope
        enforceCursorContinuationBudget(envelope, prepared.context.budget, envelope.pageNumber)
        val expectedBinding = QueryCursorLeaseBinding(
            basePlan.target,
            BackendPlanFingerprint(basePlan.fingerprint.value),
            mappingDigest,
            securityDigest,
            registration.descriptor.key.backendId,
        )
        val continued = continueAnalytics(prepared, envelope, limit)
        val continuedPlan = requireAnalyticsPlan(continued)
        val continuedRoute = resolvePlannedRoute(continued, continuedPlan)
        val continuedRegistration = continuedRoute.registry.resolve(continuedPlan)
        if (continuedRegistration.descriptor.key != registration.descriptor.key ||
            continuedRegistration.descriptor.mappingGenerationDigest != mappingDigest.value
        ) {
            rejectInvalidCursorBinding()
        }
        cursorRuntime.coordinator.acquire(loaded, expectedBinding).flatMap { acquired ->
            withCursorSession(
                CursorExecutionSession(
                    cursorRuntime.coordinator,
                    acquired,
                    QueryCursorLeaseDescriptor(
                        continuedPlan.target,
                        continuedRegistration.descriptor.key.backendId,
                        mappingDigest,
                    ),
                ),
            ) { session ->
                executeAnalyticsPage(
                    continued,
                    continuedRoute,
                    continuedPlan,
                    mappingDigest,
                    securityDigest,
                    envelope.pageNumber,
                    session,
                )
            }
        }
    }

    private fun prepare(
        request: QueryExecutionRequest,
        expectedOperation: QueryOperation,
        invocationFactory: QueryInvocationFactory,
    ): Mono<PreparedExecution> = preparePlanning(request, expectedOperation, invocationFactory).map { prepared ->
        PreparedExecution(resolveRoute(prepared), QueryExecutionOptions.from(prepared.context))
    }

    private fun preparePlanning(
        request: QueryExecutionRequest,
        expectedOperation: QueryOperation,
        invocationFactory: QueryInvocationFactory,
    ): Mono<PreparedPlanning> = Mono.defer {
        val normalized = freezeAndNormalize(request, expectedOperation, invocationFactory)
        contextFactory.resolve(request).flatMap { context ->
            val schema = schemaRegistry[normalized.target]
                ?: rejectQuery(
                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                    QueryRejectionPath.ROOT.property("target"),
                    QueryRejectionCode.SCHEMA_NOT_REGISTERED,
                )
            policyEnforcer.authorize(QueryPolicyInput(context, normalized, schema)).map { constraints ->
                val effectiveConstraints = constraints.constrain(context.budget)
                PreparedPlanning(
                    context,
                    normalized,
                    schema,
                    effectiveConstraints,
                    planner.plan(normalized, schema, effectiveConstraints),
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

    private data class PreparedPlanning(
        val context: me.ahoo.wow.query.internal.policy.QueryExecutionContext,
        val normalized: NormalizedQueryInvocation,
        val schema: me.ahoo.wow.query.backend.QueryDocumentSchema,
        val constraints: PlanningConstraints,
        val decision: PlanningDecision,
    )

    private fun resolveRoute(
        prepared: PreparedPlanning,
        plan: AnalyticsQueryPlan? = null,
    ): QueryExecutionRoute = routeResolver.resolve(
        prepared.context,
        prepared.normalized,
        prepared.schema,
        plan?.let { PlanningDecision.Planned(it) } ?: prepared.decision,
    )

    private fun resolvePlannedRoute(
        prepared: PreparedPlanning,
        plan: AnalyticsQueryPlan,
    ): QueryExecutionRoute.Planned =
        resolveRoute(prepared, plan) as? QueryExecutionRoute.Planned ?: rejectInvalidExecutionDecision()

    private fun requireAnalyticsPlan(prepared: PreparedPlanning): AnalyticsQueryPlan =
        (prepared.decision as? PlanningDecision.Planned)?.plan as? AnalyticsQueryPlan
            ?: rejectInvalidExecutionDecision()

    private fun continueAnalytics(
        prepared: PreparedPlanning,
        envelope: QueryCursorEnvelope,
        limit: Int,
    ): PreparedPlanning {
        val currentInput = prepared.normalized.input as? NormalizedQueryInput.Analytics ?: rejectInvalidInvocation()
        val position = envelope.position as? QueryCursorPosition.Analytics ?: rejectInvalidCursorBinding()
        val cursor = DecodedAnalyticsCursor(
            prepared.normalized.target,
            InternalPlanFingerprint(envelope.planFingerprint.value),
            requireNotNull(
                NonEmptyList.from(position.dimensionAliases.map { alias -> InternalAnalyticsAlias(alias.value) }),
            ),
            requireNotNull(NonEmptyList.from(position.afterKey)),
        )
        val continuedInvocation = prepared.normalized.copy(
            input = NormalizedQueryInput.Analytics(
                currentInput.query.copy(
                    bucketWindow = AnalyticsBucketWindow.After(limit, cursor),
                    cursorToken = null,
                ),
            ),
        )
        return prepared.copy(
            normalized = continuedInvocation,
            decision = planner.plan(continuedInvocation, prepared.schema, prepared.constraints),
        )
    }

    private fun executeAnalyticsPage(
        prepared: PreparedPlanning,
        route: QueryExecutionRoute.Planned,
        plan: AnalyticsQueryPlan,
        mappingDigest: QueryCursorMappingDigest,
        securityDigest: me.ahoo.wow.query.internal.cursor.QueryCursorSecurityContextDigest,
        currentPage: Int,
        session: CursorExecutionSession,
    ): Mono<AnalyticsExecutionPage> = executor.analyze(
        route,
        QueryExecutionOptions.from(prepared.context),
        session.cursorState(),
    ).flatMap { page ->
        issueNextCursor(prepared, plan, route, page, mappingDigest, securityDigest, currentPage, session)
    }

    private fun issueNextCursor(
        prepared: PreparedPlanning,
        plan: AnalyticsQueryPlan,
        route: QueryExecutionRoute.Planned,
        page: BackendAnalyticsPage,
        mappingDigest: QueryCursorMappingDigest,
        securityDigest: me.ahoo.wow.query.internal.cursor.QueryCursorSecurityContextDigest,
        currentPage: Int,
        session: CursorExecutionSession,
    ): Mono<AnalyticsExecutionPage> {
        val registration = route.registry.resolve(plan)
        val backendState = page.cursorState()?.let { payload ->
            QueryCursorBackendState(registration.descriptor.key.backendId, payload)
        }
        val afterKey = page.afterKey
        if (afterKey == null) {
            return session.closeTerminal(backendState).thenReturn(AnalyticsExecutionPage(page, null))
        }
        enforceCursorPageBudget(prepared.context.budget, currentPage + 1)
        val grouping = plan.grouping as? PlannedAnalyticsGrouping.By ?: rejectInvalidCursorBinding()
        val runtime = analyticsCursorRuntime ?: rejectCursorStoreRequired()
        val envelope = QueryCursorEnvelope(
            target = plan.target,
            planFingerprint = BackendPlanFingerprint(plan.fingerprint.value),
            mappingGenerationDigest = mappingDigest,
            securityContextDigest = securityDigest,
            position = QueryCursorPosition.Analytics(
                grouping.dimensions.values.map { dimension -> BackendAnalyticsAlias(dimension.alias.value) },
                afterKey,
            ),
            expiresAt = cursorExpiry(runtime),
            backendState = backendState,
            pageNumber = currentPage + 1,
            budgetCeiling = session.nextBudgetCeiling(prepared.context.budget),
            backendId = registration.descriptor.key.backendId,
        )
        return session.issue(envelope).map { token -> AnalyticsExecutionPage(page, token) }
    }
}

private fun cursorExpiry(runtime: AnalyticsCursorRuntime): java.time.Instant = try {
    runtime.clock.instant().plus(runtime.leaseTtl)
} catch (error: DateTimeException) {
    throw IllegalStateException("Cursor lease expiry cannot be represented.", error)
} catch (error: ArithmeticException) {
    throw IllegalStateException("Cursor lease expiry cannot be represented.", error)
}

private fun <T : Any> withCursorSession(
    session: CursorExecutionSession,
    execute: (CursorExecutionSession) -> Mono<T>,
): Mono<T> = Mono.usingWhen(
    Mono.just(session),
    execute,
    CursorExecutionSession::cleanupTerminal,
    { current, _ -> current.cleanupTerminal() },
    CursorExecutionSession::cleanupTerminal,
)

private class CursorExecutionSession(
    private val coordinator: PersistentQueryCursorLeaseCoordinator?,
    current: QueryCursorEnvelope?,
    private val descriptor: QueryCursorLeaseDescriptor,
) {
    private var active: QueryCursorEnvelope? = current
    private var settled = false

    fun cursorState(): ByteArray? = active?.backendState?.payload()

    fun nextBudgetCeiling(requested: QueryExecutionBudget): QueryCursorBudgetCeiling =
        active?.budgetCeiling?.tighten(requested) ?: requested.toCursorBudgetCeiling()

    fun issue(envelope: QueryCursorEnvelope): Mono<QueryCursorToken> {
        val owner = coordinator ?: rejectCursorStoreRequired()
        active = envelope
        return owner.issue(envelope).doOnNext { settled = true }
    }

    fun closeTerminal(state: QueryCursorBackendState?): Mono<Void> {
        val current = active
        active = current?.copy(backendState = state)
        val envelope = active
        if (envelope == null) {
            if (state != null) {
                val owner = coordinator ?: rejectCursorStoreRequired()
                return owner.close(state, descriptor, QueryCursorCleanupReason.TERMINAL).doOnSuccess { settled = true }
            }
            settled = true
            return Mono.empty()
        }
        val owner = coordinator ?: rejectCursorStoreRequired()
        return owner.close(envelope, QueryCursorCleanupReason.TERMINAL).doOnSuccess { settled = true }
    }

    fun cleanupTerminal(): Mono<Void> {
        if (settled) return Mono.empty()
        settled = true
        val envelope = active ?: return Mono.empty()
        return coordinator?.close(envelope, QueryCursorCleanupReason.TERMINAL) ?: Mono.empty()
    }
}

private fun enforceCursorPageBudget(budget: QueryExecutionBudget, pageNumber: Int) {
    if (budget.maxCursorPages?.let { maximum -> pageNumber > maximum } == true) {
        rejectQuery(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            CURSOR_PATH,
            QueryRejectionCode.CURSOR_PAGE_LIMIT_EXCEEDED,
        )
    }
}

private fun enforceCursorContinuationBudget(
    envelope: QueryCursorEnvelope,
    budget: QueryExecutionBudget,
    pageNumber: Int,
) {
    if (!envelope.budgetCeiling.allows(budget)) {
        rejectQuery(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            CURSOR_PATH,
            QueryRejectionCode.CURSOR_BUDGET_RELAXATION_NOT_ALLOWED,
        )
    }
    enforceCursorPageBudget(budget, pageNumber)
}

private fun QueryExecutionBudget.toCursorBudgetCeiling(): QueryCursorBudgetCeiling = QueryCursorBudgetCeiling(
    maxScannedRecords,
    maxReturnedRecords,
    maxPageWindow,
    maxCandidateBuckets,
    maxReturnedBuckets,
    maxCursorPages,
    allowDiskUse,
)

private fun QueryCursorBudgetCeiling.allows(requested: QueryExecutionBudget): Boolean =
    allows(maxScannedRecords, requested.maxScannedRecords) &&
        allows(maxReturnedRecords, requested.maxReturnedRecords) &&
        allows(maxPageWindow, requested.maxPageWindow) &&
        allows(maxCandidateBuckets, requested.maxCandidateBuckets) &&
        allows(maxReturnedBuckets, requested.maxReturnedBuckets) &&
        allows(maxCursorPages, requested.maxCursorPages) &&
        (allowDiskUse || !requested.allowDiskUse)

private fun QueryCursorBudgetCeiling.tighten(requested: QueryExecutionBudget): QueryCursorBudgetCeiling =
    QueryCursorBudgetCeiling(
        tighten(maxScannedRecords, requested.maxScannedRecords),
        tighten(maxReturnedRecords, requested.maxReturnedRecords),
        tighten(maxPageWindow, requested.maxPageWindow),
        tighten(maxCandidateBuckets, requested.maxCandidateBuckets),
        tighten(maxReturnedBuckets, requested.maxReturnedBuckets),
        tighten(maxCursorPages, requested.maxCursorPages),
        allowDiskUse && requested.allowDiskUse,
    )

private fun <T : Comparable<T>> allows(initial: T?, requested: T?): Boolean =
    initial == null || requested != null && requested <= initial

private fun <T : Comparable<T>> tighten(initial: T?, requested: T?): T? = when {
    initial == null -> requested
    requested == null -> initial
    else -> minOf(initial, requested)
}

private fun rejectCursorStoreRequired(): Nothing = rejectQuery(
    QueryRejectionCategory.UNSUPPORTED_FEATURE,
    CURSOR_PATH,
    QueryRejectionCode.CURSOR_STORE_REQUIRED,
)

private fun rejectInvalidCursorBinding(): Nothing = rejectQuery(
    QueryRejectionCategory.INVALID_CURSOR,
    CURSOR_PATH,
    QueryRejectionCode.INVALID_CURSOR_BINDING,
)

private fun rejectInvalidExecutionDecision(): Nothing = rejectQuery(
    QueryRejectionCategory.INTERNAL_FAILURE,
    QueryRejectionPath.ROOT.property("execution"),
    QueryRejectionCode.EXECUTION_DECISION_INVALID,
)

private fun rejectInvalidInvocation(): Nothing = rejectQuery(
    QueryRejectionCategory.INVALID_QUERY,
    QueryRejectionPath.ROOT.property("input"),
    QueryRejectionCode.INVALID_INVOCATION,
)

private val CURSOR_PATH = QueryRejectionPath.ROOT.property("cursor")

private fun PlanningConstraints.constrain(budget: QueryExecutionBudget): PlanningConstraints {
    val result = budget.maxReturnedRecords?.let { requestedMaximum ->
        val policyMaximum = (resultConstraint as? ResultPlanningConstraint.MaximumRecords)?.value
        val effectiveMaximum = policyMaximum?.coerceAtMost(requestedMaximum) ?: requestedMaximum
        ResultPlanningConstraint.MaximumRecords(effectiveMaximum)
    } ?: resultConstraint
    val page = budget.maxPageWindow?.let { requestedMaximum ->
        val policyMaximum = (pageConstraint as? me.ahoo.wow.query.internal.planning.PagePlanningConstraint.MaximumWindow)
            ?.value
        val effectiveMaximum = policyMaximum?.coerceAtMost(requestedMaximum) ?: requestedMaximum
        me.ahoo.wow.query.internal.planning.PagePlanningConstraint.MaximumWindow(effectiveMaximum)
    } ?: pageConstraint
    return copy(resultConstraint = result, pageConstraint = page)
}
