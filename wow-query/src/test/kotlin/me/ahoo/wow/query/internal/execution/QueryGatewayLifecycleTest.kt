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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.admission.RawAdmissionGuard
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.QueryNormalizer
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryFieldConstraint
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.policy.QueryAuthority
import me.ahoo.wow.query.internal.policy.QueryAuthorityProvider
import me.ahoo.wow.query.internal.policy.QueryExecutionContextFactory
import me.ahoo.wow.query.internal.policy.QueryExecutionRequest
import me.ahoo.wow.query.internal.policy.QueryPolicy
import me.ahoo.wow.query.internal.policy.QueryPolicyAllowance
import me.ahoo.wow.query.internal.policy.QueryPolicyDecision
import me.ahoo.wow.query.internal.policy.QueryPolicyEnforcer
import me.ahoo.wow.query.internal.policy.QueryPurpose
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.schema.QuerySchemaRegistry
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class QueryGatewayLifecycleTest {
    private val instant = Instant.parse("2026-08-07T00:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val record = BackendRecord(
        "order-1",
        NormalizedValue.ObjectValue(mapOf("name" to NormalizedValue.Text("Ada"))),
        BackendRecordCompleteness.COMPLETE,
    )

    @Test
    fun `gateway should remain cold and recreate the complete pipeline per subscription`() {
        val invocationReads = AtomicInteger()
        val authorityReads = AtomicInteger()
        val backendReads = AtomicInteger()
        val observer = RecordingObserver()
        val backend = StubRecordBackend(
            countAction = {
                backendReads.incrementAndGet()
                Mono.just(7)
            },
        )
        val gateway = gateway(
            backend,
            authorityProvider = QueryAuthorityProvider {
                authorityReads.incrementAndGet()
                Mono.just(QueryAuthority.System("test", "gateway-lifecycle"))
            },
            observer = observer,
        )

        val result = gateway.count(request()) {
            invocationReads.incrementAndGet()
            countInvocation()
        }
        invocationReads.get().assert().isZero()
        authorityReads.get().assert().isZero()
        backendReads.get().assert().isZero()

        result.block().assert().isEqualTo(7)
        result.block().assert().isEqualTo(7)

        invocationReads.get().assert().isEqualTo(2)
        authorityReads.get().assert().isEqualTo(2)
        backendReads.get().assert().isEqualTo(2)
        observer.starts.size.assert().isEqualTo(2)
        observer.terminals.map(QueryLifecycleTerminal::kind).assert().containsExactly(
            QueryTerminationKind.COMPLETE,
            QueryTerminationKind.COMPLETE,
        )
    }

    @Test
    fun `sync and async backend errors should cross the same safe error boundary`() {
        listOf(
            StubRecordBackend(countAction = { throw IllegalStateException("sync-secret") }),
            StubRecordBackend(countAction = { Mono.error(IllegalStateException("async-secret")) }),
        ).forEach { backend ->
            StepVerifier.create(gateway(backend).count(request(), ::countInvocation))
                .expectErrorSatisfies { error ->
                    val rejected = error as QueryRejectedException
                    rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.INTERNAL_FAILURE)
                    rejected.rejection.path.toString().assert().isEqualTo("$.backend")
                    rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.BACKEND_EXECUTION_FAILED)
                    rejected.cause.assert().isInstanceOf(IllegalStateException::class.java)
                }
                .verify()
        }
    }

    @Test
    fun `typed backend failures should map to stable rejection tuples`() {
        listOf(
            BackendFailureExpectation(
                QueryBackendFailureKind.UNAVAILABLE,
                QueryRejectionCategory.BACKEND_UNAVAILABLE,
                "$.backend",
                QueryRejectionCode.BACKEND_EXECUTION_FAILED,
            ),
            BackendFailureExpectation(
                QueryBackendFailureKind.TIMEOUT,
                QueryRejectionCategory.BACKEND_TIMEOUT,
                "$.backend",
                QueryRejectionCode.BACKEND_TIMEOUT,
            ),
            BackendFailureExpectation(
                QueryBackendFailureKind.INCOMPLETE_RESULT,
                QueryRejectionCategory.INCOMPLETE_RESULT,
                "$.backend.result",
                QueryRejectionCode.INCOMPLETE_RESULT,
            ),
            BackendFailureExpectation(
                QueryBackendFailureKind.MAPPING_FAILURE,
                QueryRejectionCategory.MAPPING_FAILURE,
                "$.result",
                QueryRejectionCode.RESULT_MAPPING_FAILED,
            ),
            BackendFailureExpectation(
                QueryBackendFailureKind.BUDGET_EXCEEDED,
                QueryRejectionCategory.BUDGET_EXCEEDED,
                "$.executionContext.budget",
                QueryRejectionCode.BACKEND_BUDGET_EXCEEDED,
            ),
        ).forEach { expectation ->
            val backend = StubRecordBackend(
                countAction = { Mono.error(QueryBackendException(expectation.kind)) },
            )
            StepVerifier.create(gateway(backend).count(request(), ::countInvocation))
                .expectErrorSatisfies { error ->
                    val rejected = error as QueryRejectedException
                    rejected.rejection.category.assert().isEqualTo(expectation.category)
                    rejected.rejection.path.toString().assert().isEqualTo(expectation.path)
                    rejected.rejection.code.assert().isEqualTo(expectation.code)
                    rejected.cause.assert().isInstanceOf(QueryBackendException::class.java)
                }
                .verify()
        }
    }

    @Test
    fun `backend should not spoof gateway stage rejection`() {
        val spoofed = QueryRejectedException(
            me.ahoo.wow.query.internal.rejection.QueryRejection(
                QueryRejectionCategory.ACCESS_DENIED,
                me.ahoo.wow.query.internal.rejection.QueryRejectionPath.ROOT.property("policy"),
                QueryRejectionCode.POLICY_DENIED,
            ),
        )
        StepVerifier.create(
            gateway(StubRecordBackend(countAction = { Mono.error(spoofed) })).count(request(), ::countInvocation),
        )
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.INTERNAL_FAILURE)
                rejected.rejection.path.toString().assert().isEqualTo("$.backend")
                rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.BACKEND_EXECUTION_FAILED)
                rejected.cause.assert().isSameAs(spoofed)
            }
            .verify()
    }

    @Test
    fun `partial stream error and cancellation should preserve signals and terminate observation once`() {
        val partialObserver = RecordingObserver()
        val partial = StubRecordBackend(
            streamAction = {
                Flux.concat(
                    Flux.just(record),
                    Flux.error(QueryBackendException(QueryBackendFailureKind.UNAVAILABLE)),
                )
            },
        )
        StepVerifier.create(gateway(partial, observer = partialObserver).stream(request(), ::streamInvocation))
            .expectNext(record)
            .expectError(QueryRejectedException::class.java)
            .verify()
        partialObserver.terminals.single().kind.assert().isEqualTo(QueryTerminationKind.ERROR)
        partialObserver.terminals.single().emitted.assert().isEqualTo(1)
        partialObserver.terminals.single().error?.rejection?.category.assert()
            .isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)

        val cancelled = AtomicBoolean()
        val cancelObserver = RecordingObserver()
        val never = StubRecordBackend(
            streamAction = {
                Flux.just(record)
                    .concatWith(Flux.never())
                    .doOnCancel { cancelled.set(true) }
            },
        )
        StepVerifier.create(gateway(never, observer = cancelObserver).stream(request(), ::streamInvocation))
            .expectNext(record)
            .thenCancel()
            .verify()
        cancelled.get().assert().isTrue()
        cancelObserver.terminals.single().kind.assert().isEqualTo(QueryTerminationKind.CANCEL)
        cancelObserver.terminals.single().error.assert().isNull()
    }

    @Test
    fun `absolute deadline should stop a continuously emitting stream and cancel upstream`() {
        val scheduler = VirtualTimeScheduler.create()
        val cancelled = AtomicBoolean()
        val backend = StubRecordBackend(
            streamAction = {
                Flux.interval(Duration.ofSeconds(1), scheduler)
                    .map { record }
                    .doOnCancel { cancelled.set(true) }
            },
        )
        val deadlineRequest = request().copy(deadline = instant.plusMillis(4_500))
        val result = gateway(backend, scheduler = scheduler).stream(deadlineRequest, ::streamInvocation)

        StepVerifier.withVirtualTime({ result }, { scheduler }, 0)
            .thenRequest(Long.MAX_VALUE)
            .thenAwait(Duration.ofSeconds(4))
            .expectNextCount(4)
            .thenAwait(Duration.ofMillis(500))
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.BUDGET_EXCEEDED)
                rejected.rejection.path.toString().assert().isEqualTo("$.executionContext.deadline")
                rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.DEADLINE_EXPIRED)
            }
            .verify()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `absolute deadline should also cancel authority resolution before backend routing`() {
        val scheduler = VirtualTimeScheduler.create()
        val authorityCancelled = AtomicBoolean()
        val backendCalls = AtomicInteger()
        val backend = StubRecordBackend(
            countAction = {
                backendCalls.incrementAndGet()
                Mono.just(1)
            },
        )
        val gateway = gateway(
            backend,
            authorityProvider = QueryAuthorityProvider {
                Mono.never<QueryAuthority>().doOnCancel { authorityCancelled.set(true) }
            },
            scheduler = scheduler,
        )
        val result = gateway.count(
            request().copy(deadline = instant.plusSeconds(2)),
            ::countInvocation,
        )

        StepVerifier.withVirtualTime({ result }, { scheduler }, 0)
            .thenRequest(1)
            .thenAwait(Duration.ofSeconds(2))
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.DEADLINE_EXPIRED)
            }
            .verify()
        authorityCancelled.get().assert().isTrue()
        backendCalls.get().assert().isZero()
    }

    @Test
    fun `mono deadline should preserve backend completion and propagate downstream cancellation`() {
        val completedSignal = AtomicReference<reactor.core.publisher.SignalType>()
        val completeObserver = RecordingObserver()
        val completed = StubRecordBackend(
            countAction = { Mono.just(7L).doFinally(completedSignal::set) },
        )
        gateway(completed, observer = completeObserver)
            .count(request().copy(deadline = instant.plusSeconds(5)), ::countInvocation)
            .block()
            .assert().isEqualTo(7L)
        completedSignal.get().assert().isEqualTo(reactor.core.publisher.SignalType.ON_COMPLETE)
        completeObserver.terminals.single().kind.assert().isEqualTo(QueryTerminationKind.COMPLETE)

        val cancelled = AtomicBoolean()
        val subscribed = Sinks.one<Unit>()
        val cancelObserver = RecordingObserver()
        val never = StubRecordBackend(
            countAction = {
                Mono.never<Long>()
                    .doOnSubscribe { subscribed.tryEmitValue(Unit) }
                    .doOnCancel { cancelled.set(true) }
            },
        )
        val subscription = gateway(never, observer = cancelObserver)
            .count(request().copy(deadline = instant.plusSeconds(5)), ::countInvocation)
            .subscribe()
        subscribed.asMono().block(Duration.ofSeconds(1))
        subscription.dispose()
        cancelled.get().assert().isTrue()
        cancelObserver.terminals.assert().hasSize(1)
        cancelObserver.terminals.single().kind.assert().isEqualTo(QueryTerminationKind.CANCEL)
        cancelObserver.terminals.single().error.assert().isNull()
    }

    @Test
    fun `observer failures should never replace query success`() {
        val observer = object : QueryLifecycleObserver {
            override fun onStart(descriptor: QueryLifecycleDescriptor) {
                error("start observer failure")
            }

            override fun onTerminal(terminal: QueryLifecycleTerminal) {
                error("terminal observer failure")
            }
        }
        gateway(StubRecordBackend(countAction = { Mono.just(9) }), observer = observer)
            .count(request(), ::countInvocation)
            .block()
            .assert().isEqualTo(9)
    }

    @Test
    fun `observer failures should never replace query error or cancellation`() {
        val observer = object : QueryLifecycleObserver {
            override fun onStart(descriptor: QueryLifecycleDescriptor) {
                error("start observer failure")
            }

            override fun onTerminal(terminal: QueryLifecycleTerminal) {
                error("terminal observer failure")
            }
        }
        val backend = StubRecordBackend(
            streamAction = {
                Flux.concat(
                    Flux.just(record),
                    Flux.error(QueryBackendException(QueryBackendFailureKind.UNAVAILABLE)),
                )
            },
        )
        StepVerifier.create(gateway(backend, observer = observer).stream(request(), ::streamInvocation))
            .expectNext(record)
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
            }
            .verify()

        val cancelled = AtomicBoolean()
        val subscribed = Sinks.one<Unit>()
        val never = StubRecordBackend(
            streamAction = {
                Flux.never<BackendRecord>()
                    .doOnSubscribe { subscribed.tryEmitValue(Unit) }
                    .doOnCancel { cancelled.set(true) }
            },
        )
        val subscription = gateway(never, observer = observer).stream(request(), ::streamInvocation).subscribe()
        subscribed.asMono().block(Duration.ofSeconds(1))
        subscription.dispose()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `gateway should freeze mutable wire input before asynchronous authority and fail closed before backend`() {
        val authority = Sinks.one<QueryAuthority>()
        val sourceChildren = mutableListOf(Condition.eq("state.name", "Ada"))
        val capturedPlan = AtomicReference<CountQueryPlan>()
        val backendCalls = AtomicInteger()
        val backend = StubRecordBackend(
            countAction = { plan ->
                capturedPlan.set(plan)
                backendCalls.incrementAndGet()
                Mono.just(1)
            },
        )
        val gateway = gateway(
            backend,
            authorityProvider = QueryAuthorityProvider { authority.asMono() },
        )
        val result = gateway.count(request()) {
            QueryInvocation(
                PlanningFixtures.target,
                QueryOperation.COUNT,
                QueryResultShape.COUNT,
                QueryInput.Count(Condition(operator = Operator.AND, children = sourceChildren)),
            )
        }

        StepVerifier.create(result)
            .then {
                sourceChildren.clear()
                authority.tryEmitValue(QueryAuthority.System("test", "freeze-boundary"))
            }
            .expectNext(1)
            .verifyComplete()
        backendCalls.get().assert().isEqualTo(1)
        val effective = capturedPlan.get().filter.user as PlannedCondition.Junction
        effective.children.values.assert().hasSize(2)

        val deniedCalls = AtomicInteger()
        val deniedBackend = StubRecordBackend(
            countAction = {
                deniedCalls.incrementAndGet()
                Mono.just(1)
            },
        )
        val missingDecision = QueryPolicy { Mono.empty() }
        StepVerifier.create(gateway(deniedBackend, policy = missingDecision).count(request(), ::countInvocation))
            .expectErrorSatisfies { error ->
                val rejected = error as QueryRejectedException
                rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.ACCESS_DENIED)
                rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.POLICY_DECISION_MISSING)
            }
            .verify()
        deniedCalls.get().assert().isZero()
    }

    @Test
    fun `unbound raw should be rejected before routing without inspecting driver object`() {
        val backendCalls = AtomicInteger()
        val backend = StubRecordBackend(
            countAction = {
                backendCalls.incrementAndGet()
                Mono.just(1)
            },
        )
        val hostileDriver = object {
            override fun toString(): String = error("driver object must not be inspected")
        }
        StepVerifier.create(
            gateway(backend).count(request()) {
                QueryInvocation(
                    PlanningFixtures.target,
                    QueryOperation.COUNT,
                    QueryResultShape.COUNT,
                    QueryInput.Count(Condition.raw(hostileDriver)),
                )
            },
        ).expectErrorSatisfies { error ->
            val rejected = error as QueryRejectedException
            rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.UNSUPPORTED_FEATURE)
            rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.NATIVE_BACKEND_UNBOUND)
        }.verify()
        backendCalls.get().assert().isZero()
    }

    private fun gateway(
        backend: StubRecordBackend,
        authorityProvider: QueryAuthorityProvider = QueryAuthorityProvider {
            Mono.just(QueryAuthority.System("test", "gateway-lifecycle"))
        },
        observer: QueryLifecycleObserver = QueryLifecycleObserver.NONE,
        scheduler: Scheduler = Schedulers.parallel(),
        policy: QueryPolicy = allowPolicy(),
    ): QueryGateway {
        val backendId = BackendId("probe")
        val registration = QueryBackendRegistration(
            QueryBackendDescriptor(
                QueryBackendKey(PlanningFixtures.target, backendId),
                PlanningFixtures.schema.contractId,
                setOf(
                    QueryOperation.SINGLE,
                    QueryOperation.STREAM,
                    QueryOperation.PAGE,
                    QueryOperation.COUNT,
                ),
                me.ahoo.wow.query.internal.plan.SemanticTier.entries.toSet(),
                PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
                PlanningFixtures.schema.searchScopes.keys,
            ),
            recordBackend = backend,
        )
        val plannedRegistry = QueryBackendRegistry(
            listOf(registration),
            mapOf(PlanningFixtures.target to backendId),
        )
        val deadlineEnforcer = QueryDeadlineEnforcer(clock, scheduler)
        return QueryGateway(
            RawAdmissionGuard(QueryAdmissionLimits.DEFAULT),
            QueryNormalizer(clock),
            QuerySchemaRegistry(listOf(PlanningFixtures.schema)),
            QueryExecutionContextFactory(authorityProvider, clock),
            QueryPolicyEnforcer(policy),
            QueryPlanner(),
            QueryExecutionRouteResolver(plannedRegistry, LegacyBackendRegistry(emptyList())),
            QueryExecutor(deadlineEnforcer),
            deadlineEnforcer,
            lifecycleMonitor = QueryLifecycleMonitor(observer),
        )
    }

    private fun request(): QueryExecutionRequest = QueryExecutionRequest(
        PlanningFixtures.target,
        QueryPurpose("test"),
        QueryExecutionMode.PLANNED,
        QueryValidationMode.STRICT,
    )

    private fun countInvocation(): QueryInvocation = QueryInvocation(
        PlanningFixtures.target,
        QueryOperation.COUNT,
        QueryResultShape.COUNT,
        QueryInput.Count(Condition.deleted(DeletionState.ALL)),
    )

    private fun streamInvocation(): QueryInvocation = QueryInvocation(
        PlanningFixtures.target,
        QueryOperation.STREAM,
        QueryResultShape.DYNAMIC,
        QueryInput.Stream(ListQuery(Condition.deleted(DeletionState.ALL), limit = 10)),
    )

    private fun allowPolicy(): QueryPolicy = QueryPolicy {
        Mono.just(
            QueryPolicyDecision.Allow(
                QueryPolicyAllowance.builder()
                    .fieldConstraint(QueryFieldConstraint())
                    .build(),
            ),
        )
    }

    private class RecordingObserver : QueryLifecycleObserver {
        val starts = mutableListOf<QueryLifecycleDescriptor>()
        val terminals = mutableListOf<QueryLifecycleTerminal>()

        override fun onStart(descriptor: QueryLifecycleDescriptor) {
            starts += descriptor
        }

        override fun onTerminal(terminal: QueryLifecycleTerminal) {
            terminals += terminal
        }
    }

    private data class BackendFailureExpectation(
        val kind: QueryBackendFailureKind,
        val category: QueryRejectionCategory,
        val path: String,
        val code: QueryRejectionCode,
    )

    private class StubRecordBackend(
        private val singleAction: (SingleQueryPlan) -> Mono<BackendRecord> = { Mono.empty() },
        private val streamAction: (StreamQueryPlan) -> Flux<BackendRecord> = { Flux.empty() },
        private val pageAction: (PageQueryPlan) -> Mono<BackendPage> = { Mono.empty() },
        private val countAction: (CountQueryPlan) -> Mono<Long> = { Mono.empty() },
    ) : RecordQueryBackend {
        override fun single(plan: SingleQueryPlan, options: QueryExecutionOptions): Mono<BackendRecord> =
            singleAction(plan)

        override fun stream(plan: StreamQueryPlan, options: QueryExecutionOptions): Flux<BackendRecord> =
            streamAction(plan)

        override fun page(plan: PageQueryPlan, options: QueryExecutionOptions): Mono<BackendPage> = pageAction(plan)

        override fun count(plan: CountQueryPlan, options: QueryExecutionOptions): Mono<Long> = countAction(plan)
    }
}
