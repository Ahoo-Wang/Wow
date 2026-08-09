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

import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.PlanFingerprint
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.plan.StreamLimit
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal sealed interface QueryShadowTask {
    val target: QueryTarget
    val fingerprint: PlanFingerprint
    val semanticTier: SemanticTier
    val operation: QueryOperation
    val publisher: Publisher<*>

    data class Single(
        override val target: QueryTarget,
        override val fingerprint: PlanFingerprint,
        override val semanticTier: SemanticTier,
        override val publisher: Mono<BackendRecord>,
    ) : QueryShadowTask {
        override val operation: QueryOperation = QueryOperation.SINGLE
    }

    data class Stream(
        override val target: QueryTarget,
        override val fingerprint: PlanFingerprint,
        override val semanticTier: SemanticTier,
        override val publisher: Flux<BackendRecord>,
    ) : QueryShadowTask {
        override val operation: QueryOperation = QueryOperation.STREAM
    }

    data class Page(
        override val target: QueryTarget,
        override val fingerprint: PlanFingerprint,
        override val semanticTier: SemanticTier,
        override val publisher: Mono<BackendPage>,
    ) : QueryShadowTask {
        override val operation: QueryOperation = QueryOperation.PAGE
    }

    data class Count(
        override val target: QueryTarget,
        override val fingerprint: PlanFingerprint,
        override val semanticTier: SemanticTier,
        override val publisher: Mono<Long>,
    ) : QueryShadowTask {
        override val operation: QueryOperation = QueryOperation.COUNT
    }
}

internal data class QueryShadowDescriptor(
    val target: QueryTarget,
    val fingerprint: PlanFingerprint,
    val semanticTier: SemanticTier,
    val operation: QueryOperation,
)

private fun QueryShadowTask.descriptor(): QueryShadowDescriptor = QueryShadowDescriptor(
    target,
    fingerprint,
    semanticTier,
    operation,
)

internal sealed interface QueryShadowPrimarySignal {
    data class RecordValue(val value: BackendRecord) : QueryShadowPrimarySignal

    data class PageValue(val value: BackendPage) : QueryShadowPrimarySignal

    data class CountValue(val value: Long) : QueryShadowPrimarySignal

    data object Complete : QueryShadowPrimarySignal

    data class Error(val error: QueryRejectedException) : QueryShadowPrimarySignal

    data object Cancelled : QueryShadowPrimarySignal
}

internal interface QueryShadowHandle {
    fun onPrimary(signal: QueryShadowPrimarySignal)

    fun cancelProbe()

    companion object {
        val NONE: QueryShadowHandle = object : QueryShadowHandle {
            override fun onPrimary(signal: QueryShadowPrimarySignal) = Unit

            override fun cancelProbe() = Unit
        }
    }
}

internal data class QueryShadowSkip(
    val target: QueryTarget,
    val operation: QueryOperation,
    val issues: NonEmptyList<QueryRejection>,
)

internal sealed interface QueryShadowSubmission {
    data class Accepted(val handle: QueryShadowHandle) : QueryShadowSubmission

    data class Rejected(val issue: QueryRejection) : QueryShadowSubmission
}

internal fun interface QueryShadowSupervisor {
    fun submit(task: QueryShadowTask): QueryShadowSubmission

    fun onSkipped(skip: QueryShadowSkip) = Unit

    companion object {
        val DISABLED: QueryShadowSupervisor = QueryShadowSupervisor {
            QueryShadowSubmission.Rejected(shadowSupervisorUnavailable())
        }
    }
}

internal data class QueryShadowSupervisorFailure(
    val task: QueryShadowDescriptor,
    val issue: QueryRejection,
    val cause: Throwable? = null,
)

internal interface QueryDecisionObserver {
    fun onFallback(fallback: QueryFallback) = Unit

    fun onShadowSupervisorFailure(failure: QueryShadowSupervisorFailure) = Unit

    companion object {
        val NONE: QueryDecisionObserver = object : QueryDecisionObserver {}
    }
}

internal class QueryExecutor(
    private val deadlineEnforcer: QueryDeadlineEnforcer,
    private val errorBoundary: QueryErrorBoundary = QueryErrorBoundary(),
    private val shadowSupervisor: QueryShadowSupervisor = QueryShadowSupervisor.DISABLED,
    private val decisionObserver: QueryDecisionObserver = QueryDecisionObserver.NONE,
    private val shadowProbeTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!shadowProbeTimeout.isZero && !shadowProbeTimeout.isNegative) {
            "Shadow probe timeout must be positive."
        }
    }
    fun single(route: QueryExecutionRoute, options: QueryExecutionOptions): Mono<BackendRecord> =
        when (route) {
            is QueryExecutionRoute.Planned -> plannedSingle(route.registry, route.plan, options)
            is QueryExecutionRoute.Legacy -> legacyMono(route) { route.binding.single(route.input, options) }
            is QueryExecutionRoute.Shadow -> shadowMono(
                route.legacyBinding.single(route.legacyInput, options),
                plannedSingle(route.plannedRegistry, route.plan, options),
                options,
                QueryShadowPrimarySignal::RecordValue,
            ) { probe ->
                QueryShadowTask.Single(route.plan.target, route.plan.fingerprint, route.plan.semanticTier, probe)
            }
        }

    fun stream(route: QueryExecutionRoute, options: QueryExecutionOptions): Flux<BackendRecord> =
        when (route) {
            is QueryExecutionRoute.Planned -> plannedStream(route.registry, route.plan, options)
            is QueryExecutionRoute.Legacy -> legacyFlux(route) {
                enforceLegacyStreamLimit(route.input, route.binding.stream(route.input, options))
            }

            is QueryExecutionRoute.Shadow -> shadowStream(route, options)
        }

    fun page(route: QueryExecutionRoute, options: QueryExecutionOptions): Mono<BackendPage> {
        val result = when (route) {
            is QueryExecutionRoute.Planned -> plannedPage(route.registry, route.plan, options)
            is QueryExecutionRoute.Legacy -> legacyMono(route) {
                route.binding.page(route.input, options).map { page -> requireLegacyPage(route.input, page) }
            }

            is QueryExecutionRoute.Shadow -> shadowMono(
                route.legacyBinding.page(route.legacyInput, options)
                    .map { page -> requireLegacyPage(route.legacyInput, page) }
                    .switchIfEmpty(incompleteResult()),
                plannedPage(route.plannedRegistry, route.plan, options),
                options,
                QueryShadowPrimarySignal::PageValue,
            ) { probe ->
                QueryShadowTask.Page(route.plan.target, route.plan.fingerprint, route.plan.semanticTier, probe)
            }
        }
        return result.switchIfEmpty(incompleteResult())
    }

    fun count(route: QueryExecutionRoute, options: QueryExecutionOptions): Mono<Long> {
        val result = when (route) {
            is QueryExecutionRoute.Planned -> plannedCount(route.registry, route.plan, options)
            is QueryExecutionRoute.Legacy -> legacyMono(route) { route.binding.count(route.input, options) }
            is QueryExecutionRoute.Shadow -> shadowMono(
                route.legacyBinding.count(route.legacyInput, options)
                    .map(::requireNonNegativeCount)
                    .switchIfEmpty(incompleteResult()),
                plannedCount(route.plannedRegistry, route.plan, options),
                options,
                QueryShadowPrimarySignal::CountValue,
            ) { probe ->
                QueryShadowTask.Count(route.plan.target, route.plan.fingerprint, route.plan.semanticTier, probe)
            }
        }
        return result.map(::requireNonNegativeCount).switchIfEmpty(incompleteResult())
    }

    fun analyze(
        route: QueryExecutionRoute,
        options: QueryExecutionOptions,
        cursorState: ByteArray? = null,
    ): Mono<BackendAnalyticsPage> {
        val result = when (route) {
            is QueryExecutionRoute.Planned -> plannedAnalytics(route.registry, route.plan, options, cursorState)
            is QueryExecutionRoute.Legacy,
            is QueryExecutionRoute.Shadow,
            -> rejectExecution(QueryRejectionCode.EXECUTION_MODE_UNSUPPORTED)
        }
        return result.switchIfEmpty(incompleteResult())
    }

    private fun plannedSingle(
        registration: QueryBackendRegistration,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<BackendRecord> = Mono.defer {
        val backend = requireRecordBackend(registration)
        backendMono { backend.single(requirePlan(plan), options) }.map(::requireCompleteRecord)
    }

    private fun plannedSingle(
        registry: QueryBackendRegistry,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<BackendRecord> = Mono.defer { plannedSingle(registry.resolve(plan), plan, options) }

    private fun plannedStream(
        registration: QueryBackendRegistration,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer {
        val streamPlan = requirePlan<StreamQueryPlan>(plan)
        val backend = requireRecordBackend(registration)
        val records = backendFlux { backend.stream(streamPlan, options) }.map(::requireCompleteRecord)
        enforceStreamLimit(records, streamPlan.limit)
    }

    private fun plannedStream(
        registry: QueryBackendRegistry,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer { plannedStream(registry.resolve(plan), plan, options) }

    private fun plannedPage(
        registration: QueryBackendRegistration,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<BackendPage> = Mono.defer {
        val pagePlan = requirePlan<PageQueryPlan>(plan)
        val backend = requireRecordBackend(registration)
        backendMono { backend.page(pagePlan, options) }
            .map { page -> requireCompletePage(pagePlan, page) }
            .switchIfEmpty(incompleteResult())
    }

    private fun plannedPage(
        registry: QueryBackendRegistry,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<BackendPage> = Mono.defer { plannedPage(registry.resolve(plan), plan, options) }

    private fun plannedCount(
        registration: QueryBackendRegistration,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<Long> = Mono.defer {
        val backend = requireRecordBackend(registration)
        backendMono { backend.count(requirePlan(plan), options) }
            .map(::requireNonNegativeCount)
            .switchIfEmpty(incompleteResult())
    }

    private fun plannedCount(
        registry: QueryBackendRegistry,
        plan: QueryPlan,
        options: QueryExecutionOptions,
    ): Mono<Long> = Mono.defer { plannedCount(registry.resolve(plan), plan, options) }

    private fun plannedAnalytics(
        registration: QueryBackendRegistration,
        plan: QueryPlan,
        options: QueryExecutionOptions,
        cursorState: ByteArray?,
    ): Mono<BackendAnalyticsPage> = Mono.defer {
        val analyticsPlan = requirePlan<AnalyticsQueryPlan>(plan)
        val backend = registration.analyticsBackend
            ?: rejectExecution(QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED)
        backendMono { backend.analyze(analyticsPlan, options, cursorState) }.map { page ->
            if (!page.isCompleteFor(analyticsPlan)) {
                rejectIncomplete()
            }
            page
        }.switchIfEmpty(incompleteResult())
    }

    private fun plannedAnalytics(
        registry: QueryBackendRegistry,
        plan: QueryPlan,
        options: QueryExecutionOptions,
        cursorState: ByteArray?,
    ): Mono<BackendAnalyticsPage> = Mono.defer {
        plannedAnalytics(registry.resolve(plan), plan, options, cursorState)
    }

    private fun shadowStream(
        route: QueryExecutionRoute.Shadow,
        options: QueryExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer {
        val plan = requirePlan<StreamQueryPlan>(route.plan)
        val primary = enforceLegacyStreamLimit(
            route.legacyInput,
            route.legacyBinding.stream(route.legacyInput, options),
        )
        if (plan.limit == StreamLimit.Unbounded) {
            notifyShadowSkipped(
                route.legacyInput.invocation.target,
                route.legacyInput.invocation.operation,
                NonEmptyList.of(
                    QueryRejection(
                        QueryRejectionCategory.UNSUPPORTED_FEATURE,
                        QueryRejectionPath.ROOT.property("shadow"),
                        QueryRejectionCode.SHADOW_PROBE_UNBOUNDED_STREAM,
                    ),
                ),
            )
            primary
        } else {
            shadowFlux(
                plan,
                primary,
                plannedStream(route.plannedRegistry, route.plan, options),
                options,
            )
        }
    }

    private fun <T : Any, P : Any> shadowMono(
        primary: Mono<T>,
        probe: Mono<P>,
        options: QueryExecutionOptions,
        signal: (T) -> QueryShadowPrimarySignal,
        taskFactory: (Mono<P>) -> QueryShadowTask,
    ): Mono<T> = Mono.defer {
        val handle = submitShadow(taskFactory(normalizeShadowMono(options, probe)))
        primary
            .doOnNext { value -> reportPrimary(handle, signal(value)) }
            .doOnSuccess { reportPrimary(handle, QueryShadowPrimarySignal.Complete) }
            .doOnError { error -> reportPrimaryError(handle, error) }
            .doOnCancel { cancelShadow(handle) }
    }

    private fun shadowFlux(
        plan: QueryPlan,
        primary: Flux<BackendRecord>,
        probe: Flux<BackendRecord>,
        options: QueryExecutionOptions,
    ): Flux<BackendRecord> = Flux.defer {
        val normalizedProbe = normalizeShadowFlux(options, probe)
        val handle = submitShadow(
            QueryShadowTask.Stream(plan.target, plan.fingerprint, plan.semanticTier, normalizedProbe),
        )
        primary
            .doOnNext { value -> reportPrimary(handle, QueryShadowPrimarySignal.RecordValue(value)) }
            .doOnComplete { reportPrimary(handle, QueryShadowPrimarySignal.Complete) }
            .doOnError { error -> reportPrimaryError(handle, error) }
            .doOnCancel { cancelShadow(handle) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun submitShadow(task: QueryShadowTask): QueryShadowHandle = try {
        when (val submission = shadowSupervisor.submit(task)) {
            is QueryShadowSubmission.Accepted -> submission.handle
            is QueryShadowSubmission.Rejected -> {
                notifyShadowSupervisorFailure(task, submission.issue)
                QueryShadowHandle.NONE
            }
        }
    } catch (error: RuntimeException) {
        notifyShadowSupervisorFailure(task, shadowSupervisorUnavailable(), error)
        QueryShadowHandle.NONE
    }

    private fun reportPrimary(handle: QueryShadowHandle, signal: QueryShadowPrimarySignal) {
        try {
            handle.onPrimary(signal)
        } catch (_: RuntimeException) {
            // Shadow comparison cannot alter the primary result.
        }
    }

    private fun reportPrimaryError(handle: QueryShadowHandle, error: Throwable) {
        reportPrimary(handle, QueryShadowPrimarySignal.Error(errorBoundary.normalize(error) as QueryRejectedException))
        cancelProbe(handle)
    }

    private fun cancelShadow(handle: QueryShadowHandle) {
        reportPrimary(handle, QueryShadowPrimarySignal.Cancelled)
        cancelProbe(handle)
    }

    private fun cancelProbe(handle: QueryShadowHandle) {
        try {
            handle.cancelProbe()
        } catch (_: RuntimeException) {
            // Shadow cleanup cannot alter the primary result.
        }
    }

    private fun <T : Any> normalizeShadowMono(options: QueryExecutionOptions, probe: Mono<T>): Mono<T> =
        deadlineEnforcer.enforceMono(shadowDeadline(options)) { probe }.onErrorMap(errorBoundary::normalize)

    private fun <T : Any> normalizeShadowFlux(options: QueryExecutionOptions, probe: Flux<T>): Flux<T> =
        deadlineEnforcer.enforceFlux(shadowDeadline(options)) { probe }.onErrorMap(errorBoundary::normalize)

    private fun shadowDeadline(options: QueryExecutionOptions) =
        deadlineEnforcer.cappedDeadline(options.deadline, shadowProbeTimeout)

    private fun <T : Any> backendMono(source: () -> Mono<T>): Mono<T> =
        Mono.defer(source).onErrorMap(errorBoundary::normalizeBackend)

    private fun <T : Any> backendFlux(source: () -> Flux<T>): Flux<T> =
        Flux.defer(source).onErrorMap(errorBoundary::normalizeBackend)

    private fun <T : Any> legacyMono(route: QueryExecutionRoute.Legacy, source: () -> Mono<T>): Mono<T> = Mono.defer {
        notifyFallback(route)
        source()
    }

    private fun <T : Any> legacyFlux(route: QueryExecutionRoute.Legacy, source: () -> Flux<T>): Flux<T> = Flux.defer {
        notifyFallback(route)
        source()
    }

    private fun notifyFallback(route: QueryExecutionRoute.Legacy) {
        route.fallback?.let { fallback ->
            try {
                decisionObserver.onFallback(fallback)
            } catch (_: RuntimeException) {
                // Decision observability cannot alter the legacy result.
            }
            if (fallback.executionMode == QueryExecutionMode.SHADOW) {
                notifyShadowSkipped(fallback.target, fallback.operation, fallback.issues)
            }
        }
    }

    private fun notifyShadowSupervisorFailure(
        task: QueryShadowTask,
        issue: QueryRejection,
        cause: Throwable? = null,
    ) {
        try {
            decisionObserver.onShadowSupervisorFailure(
                QueryShadowSupervisorFailure(task.descriptor(), issue, cause),
            )
        } catch (_: RuntimeException) {
            // Decision observability cannot alter the primary result.
        }
    }

    private fun notifyShadowSkipped(
        target: QueryTarget,
        operation: QueryOperation,
        issues: NonEmptyList<QueryRejection>,
    ) {
        try {
            shadowSupervisor.onSkipped(QueryShadowSkip(target, operation, issues))
        } catch (_: RuntimeException) {
            // Shadow observability cannot alter the legacy result.
        }
    }

    private fun enforceLegacyStreamLimit(
        input: LegacyCompilationInput,
        source: Flux<BackendRecord>,
    ): Flux<BackendRecord> {
        val stream = input.invocation.input as? NormalizedQueryInput.Stream
            ?: rejectExecution(QueryRejectionCode.EXECUTION_DECISION_INVALID)
        val limit = if (stream.limit == 0) StreamLimit.Unbounded else StreamLimit.Bounded(stream.limit)
        return enforceStreamLimit(source, limit)
    }

    private fun enforceStreamLimit(source: Flux<BackendRecord>, limit: StreamLimit): Flux<BackendRecord> =
        when (limit) {
            StreamLimit.Unbounded -> source
            is StreamLimit.Bounded -> source.index().map { indexed ->
                if (indexed.t1 >= limit.value) {
                    rejectIncomplete()
                }
                indexed.t2
            }
        }

    private fun requireCompletePage(plan: PageQueryPlan, page: BackendPage): BackendPage {
        val expectedRecordCount = if (page.total <= plan.page.offset) {
            0
        } else {
            minOf(plan.page.size.toLong(), page.total - plan.page.offset).toInt()
        }
        val completeEnvelope = page.totalRelation == BackendTotalRelation.EXACT &&
            page.consistency == BackendPageConsistency.SAME_INPUT &&
            page.records.size == expectedRecordCount &&
            page.records.size <= plan.page.size &&
            page.records.all { record -> record.completeness == BackendRecordCompleteness.COMPLETE }
        if (!completeEnvelope) {
            rejectIncomplete()
        }
        return page
    }

    private fun requireLegacyPage(input: LegacyCompilationInput, page: BackendPage): BackendPage {
        val pageInput = input.invocation.input as? NormalizedQueryInput.Page
            ?: rejectExecution(QueryRejectionCode.EXECUTION_DECISION_INVALID)
        if (page.records.size > pageInput.page.size) {
            rejectIncomplete()
        }
        return page
    }

    private fun requireRecordBackend(registration: QueryBackendRegistration): RecordQueryBackend =
        registration.recordBackend
            ?: registration.experimentalRecordBackend?.let(::ExperimentalRecordBackendAdapter)
            ?: rejectExecution(QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED)

    private fun requireCompleteRecord(record: BackendRecord): BackendRecord {
        if (record.completeness != BackendRecordCompleteness.COMPLETE) {
            rejectIncomplete()
        }
        return record
    }

    private fun requireNonNegativeCount(count: Long): Long {
        if (count < 0) {
            rejectIncomplete()
        }
        return count
    }

    private fun AnalyticsConsistency.satisfies(required: AnalyticsConsistency): Boolean =
        this == required || this == AnalyticsConsistency.SNAPSHOT && required == AnalyticsConsistency.EVENTUAL

    private fun AnalyticsCompleteness.satisfies(required: AnalyticsCompleteness): Boolean =
        this == required || this == AnalyticsCompleteness.EXACT && required == AnalyticsCompleteness.APPROXIMATE

    private fun BackendAnalyticsPage.isCompleteFor(plan: AnalyticsQueryPlan): Boolean =
        consistency.satisfies(plan.requiredConsistency) &&
            completeness.satisfies(plan.requiredCompleteness) &&
            buckets.size <= plan.bucketWindow.limit &&
            hasExpectedCursorState(plan) &&
            hasExpectedAnalyticsShape(plan)

    private fun BackendAnalyticsPage.hasExpectedCursorState(plan: AnalyticsQueryPlan): Boolean = when {
        plan.grouping is me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping.Global -> cursorState() == null
        consistency == AnalyticsConsistency.EVENTUAL -> cursorState() == null
        consistency == AnalyticsConsistency.SNAPSHOT -> cursorState() != null
        else -> false
    }

    private fun BackendAnalyticsPage.hasExpectedAnalyticsShape(plan: AnalyticsQueryPlan): Boolean {
        val (dimensionAliases, cursorMatches) = when (val grouping = plan.grouping) {
            me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping.Global ->
                emptySet<AnalyticsAlias>() to (afterKey == null)

            is me.ahoo.wow.query.internal.plan.PlannedAnalyticsGrouping.By -> {
                val aliases = grouping.dimensions.values.mapTo(LinkedHashSet()) { dimension -> dimension.alias }
                aliases to (afterKey == null || afterKey.size == aliases.size)
            }
        }
        val metricAliases = plan.metrics.values.mapTo(LinkedHashSet()) { metric -> metric.alias }
        return cursorMatches && buckets.all { bucket ->
            bucket.keys.keys == dimensionAliases && bucket.metrics.keys == metricAliases
        }
    }

    private inline fun <reified P : QueryPlan> requirePlan(plan: QueryPlan): P =
        plan as? P ?: rejectExecution(QueryRejectionCode.EXECUTION_DECISION_INVALID)

    private fun rejectIncomplete(): Nothing = rejectQuery(
        QueryRejectionCategory.INCOMPLETE_RESULT,
        QueryRejectionPath.ROOT.property("backend").property("result"),
        QueryRejectionCode.INCOMPLETE_RESULT,
    )

    private fun <T : Any> incompleteResult(): Mono<T> = Mono.defer { rejectIncomplete() }

    private fun rejectExecution(code: QueryRejectionCode): Nothing = rejectQuery(
        QueryRejectionCategory.INTERNAL_FAILURE,
        QueryRejectionPath.ROOT.property("execution"),
        code,
    )
}

private fun shadowSupervisorUnavailable(): QueryRejection = QueryRejection(
    QueryRejectionCategory.BACKEND_UNAVAILABLE,
    QueryRejectionPath.ROOT.property("shadow").property("supervisor"),
    QueryRejectionCode.SHADOW_SUPERVISOR_UNAVAILABLE,
)
