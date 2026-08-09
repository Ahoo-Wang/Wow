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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.plan.SingleQueryPlan
import me.ahoo.wow.query.internal.plan.StreamQueryPlan
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.scheduler.Schedulers
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class QueryExecutorTest {
    private val options = QueryExecutionOptions(null, QueryExecutionBudget())
    private val invocation = NormalizedQueryInvocation(
        PlanningFixtures.target,
        QueryOperation.COUNT,
        QueryResultShape.COUNT,
        NormalizedQueryInput.Count(NormalizedCondition.All, NormalizedDeletionScope.EXPLICIT),
    )
    private val decision = QueryPlanner().plan(
        invocation,
        PlanningFixtures.schema,
        PlanningConstraints(QueryValidationMode.STRICT),
    ) as PlanningDecision.Planned
    private val plan = decision.plan as CountQueryPlan
    private val deadlineEnforcer = QueryDeadlineEnforcer(Clock.systemUTC(), Schedulers.parallel())

    @Test
    fun `shadow failure and supervisor failure should never alter legacy result`() {
        val probeError = AtomicReference<Throwable>()
        val plannedCalls = AtomicInteger()
        val supervisor = QueryShadowSupervisor { task ->
            Flux.from(task.publisher).subscribe({}, probeError::set)
            QueryShadowSubmission.Accepted(QueryShadowHandle.NONE)
        }
        val route = shadowRoute(
            plannedBackend = StubRecordBackend(
                countAction = {
                    plannedCalls.incrementAndGet()
                    Mono.error(QueryBackendException(QueryBackendFailureKind.UNAVAILABLE))
                },
            ),
            legacyCount = { Mono.just(11) },
        )

        QueryExecutor(
            deadlineEnforcer,
            shadowSupervisor = supervisor,
        ).count(route, options).block().assert().isEqualTo(11)
        plannedCalls.get().assert().isEqualTo(1)
        val normalizedProbeError = probeError.get() as QueryRejectedException
        normalizedProbeError.rejection.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
        normalizedProbeError.rejection.code.assert().isEqualTo(QueryRejectionCode.BACKEND_EXECUTION_FAILED)

        val throwingSupervisor = QueryShadowSupervisor { error("shadow supervisor unavailable") }
        val unavailable = AtomicReference<QueryShadowSupervisorFailure>()
        val decisionObserver = object : QueryDecisionObserver {
            override fun onShadowSupervisorFailure(failure: QueryShadowSupervisorFailure) {
                unavailable.set(failure)
            }
        }
        QueryExecutor(
            deadlineEnforcer,
            shadowSupervisor = throwingSupervisor,
            decisionObserver = decisionObserver,
        ).count(route, options).block()
            .assert().isEqualTo(11)
        unavailable.get().task.target.assert().isEqualTo(PlanningFixtures.target)
        unavailable.get().task.operation.assert().isEqualTo(QueryOperation.COUNT)
        unavailable.get().issue.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
        unavailable.get().issue.path.toString().assert().isEqualTo("$.shadow.supervisor")
        unavailable.get().issue.code.assert().isEqualTo(QueryRejectionCode.SHADOW_SUPERVISOR_UNAVAILABLE)
        unavailable.get().cause?.message.assert().isEqualTo("shadow supervisor unavailable")
    }

    @Test
    fun `disabled or declined shadow submission should emit a typed health event without altering primary`() {
        val route = shadowRoute(
            plannedBackend = StubRecordBackend(countAction = { Mono.just(9L) }),
            legacyCount = { Mono.just(11L) },
        )
        val disabledFailure = AtomicReference<QueryShadowSupervisorFailure>()
        val disabledObserver = object : QueryDecisionObserver {
            override fun onShadowSupervisorFailure(failure: QueryShadowSupervisorFailure) {
                disabledFailure.set(failure)
            }
        }

        QueryExecutor(deadlineEnforcer, decisionObserver = disabledObserver)
            .count(route, options).block().assert().isEqualTo(11L)
        disabledFailure.get().issue.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
        disabledFailure.get().issue.path.toString().assert().isEqualTo("$.shadow.supervisor")
        disabledFailure.get().issue.code.assert().isEqualTo(QueryRejectionCode.SHADOW_SUPERVISOR_UNAVAILABLE)
        disabledFailure.get().cause.assert().isNull()

        val declinedFailures = mutableListOf<QueryShadowSupervisorFailure>()
        val decliningSupervisor = QueryShadowSupervisor {
            QueryShadowSubmission.Rejected(disabledFailure.get().issue)
        }
        val throwingObserver = object : QueryDecisionObserver {
            override fun onShadowSupervisorFailure(failure: QueryShadowSupervisorFailure) {
                declinedFailures += failure
                error("observer unavailable")
            }
        }
        QueryExecutor(
            deadlineEnforcer,
            shadowSupervisor = decliningSupervisor,
            decisionObserver = throwingObserver,
        ).count(route, options).block().assert().isEqualTo(11L)
        declinedFailures.assert().hasSize(1)
        declinedFailures.single().task.target.assert().isEqualTo(PlanningFixtures.target)
    }

    @Test
    fun `legacy error should cancel the supervised shadow task`() {
        val cancelled = AtomicBoolean()
        val supervisor = QueryShadowSupervisor {
            QueryShadowSubmission.Accepted(object : QueryShadowHandle {
                override fun onPrimary(signal: QueryShadowPrimarySignal) = Unit

                override fun cancelProbe() {
                    cancelled.set(true)
                }
            })
        }
        val legacyError = IllegalStateException("legacy-primary-error")
        val route = shadowRoute(
            plannedBackend = StubRecordBackend(countAction = { Mono.never() }),
            legacyCount = { Mono.error(legacyError) },
        )

        try {
            QueryExecutor(deadlineEnforcer, shadowSupervisor = supervisor).count(route, options).block()
        } catch (error: RuntimeException) {
            val rejected = error as QueryRejectedException
            rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.INTERNAL_FAILURE)
            rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.BACKEND_EXECUTION_FAILED)
            rejected.cause.assert().isSameAs(legacyError)
        }
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `successful mono primary should complete without cancelling the shadow probe`() {
        val terminal = AtomicReference<SignalType>()
        val cancelled = AtomicBoolean()
        val signals = mutableListOf<QueryShadowPrimarySignal>()
        val supervisor = QueryShadowSupervisor {
            QueryShadowSubmission.Accepted(object : QueryShadowHandle {
                override fun onPrimary(signal: QueryShadowPrimarySignal) {
                    signals += signal
                }

                override fun cancelProbe() {
                    cancelled.set(true)
                }
            })
        }
        val route = shadowRoute(
            plannedBackend = StubRecordBackend(countAction = { Mono.just(9L) }),
            legacyCount = { Mono.just(11L).doFinally(terminal::set) },
        )

        QueryExecutor(deadlineEnforcer, shadowSupervisor = supervisor).count(route, options).block()
            .assert().isEqualTo(11)
        terminal.get().assert().isEqualTo(SignalType.ON_COMPLETE)
        cancelled.get().assert().isFalse()
        signals.assert().containsExactly(
            QueryShadowPrimarySignal.CountValue(11L),
            QueryShadowPrimarySignal.Complete,
        )
    }

    @Test
    fun `shadow registry failure and deadline should remain isolated from the legacy primary`() {
        val instant = Instant.parse("2026-08-07T00:00:00Z")
        val scheduler = VirtualTimeScheduler.create()
        val probeError = AtomicReference<Throwable>()
        val plannedCancelled = AtomicBoolean()
        val supervisor = QueryShadowSupervisor { task ->
            Flux.from(task.publisher).subscribe({}, probeError::set)
            QueryShadowSubmission.Accepted(QueryShadowHandle.NONE)
        }
        val emptyRegistryRoute = shadowRoute(
            plannedBackend = StubRecordBackend(countAction = { Mono.just(9) }),
            legacyCount = { Mono.just(11) },
        ).copy(plannedRegistry = QueryBackendRegistry(emptyList(), emptyMap()))
        val executor = QueryExecutor(
            QueryDeadlineEnforcer(Clock.fixed(instant, ZoneOffset.UTC), scheduler),
            shadowSupervisor = supervisor,
        )

        executor.count(emptyRegistryRoute, options).block().assert().isEqualTo(11)
        val missing = probeError.get() as QueryRejectedException
        missing.rejection.category.assert().isEqualTo(QueryRejectionCategory.BACKEND_UNAVAILABLE)
        missing.rejection.code.assert().isEqualTo(QueryRejectionCode.BACKEND_NOT_REGISTERED)

        probeError.set(null)
        val deadlineRoute = shadowRoute(
            plannedBackend = StubRecordBackend(
                countAction = { Mono.never<Long>().doOnCancel { plannedCancelled.set(true) } },
            ),
            legacyCount = { Mono.just(11) },
        )
        val deadlineOptions = QueryExecutionOptions(instant.plusSeconds(5), QueryExecutionBudget())
        executor.count(deadlineRoute, deadlineOptions).block().assert().isEqualTo(11)
        scheduler.advanceTimeBy(Duration.ofSeconds(5))

        plannedCancelled.get().assert().isTrue()
        val expired = probeError.get() as QueryRejectedException
        expired.rejection.category.assert().isEqualTo(QueryRejectionCategory.BUDGET_EXCEEDED)
        expired.rejection.path.toString().assert().isEqualTo("$.executionContext.deadline")
        expired.rejection.code.assert().isEqualTo(QueryRejectionCode.DEADLINE_EXPIRED)

        probeError.set(null)
        val noDeadlineCancelled = AtomicBoolean()
        val noDeadlineRoute = shadowRoute(
            plannedBackend = StubRecordBackend(
                countAction = { Mono.never<Long>().doOnCancel { noDeadlineCancelled.set(true) } },
            ),
            legacyCount = { Mono.just(11) },
        )
        executor.count(noDeadlineRoute, options).block().assert().isEqualTo(11)
        scheduler.advanceTimeBy(Duration.ofSeconds(30))

        noDeadlineCancelled.get().assert().isTrue()
        val capped = probeError.get() as QueryRejectedException
        capped.rejection.category.assert().isEqualTo(QueryRejectionCategory.BUDGET_EXCEEDED)
        capped.rejection.path.toString().assert().isEqualTo("$.executionContext.deadline")
        capped.rejection.code.assert().isEqualTo(QueryRejectionCode.DEADLINE_EXPIRED)
    }

    @Test
    fun `planned records should require explicit completeness while legacy preserves unknown provenance`() {
        val singleInvocation = PlanningFixtures.single(resultShape = QueryResultShape.DYNAMIC)
        val singleDecision = QueryPlanner().plan(
            singleInvocation,
            PlanningFixtures.schema,
            PlanningConstraints(QueryValidationMode.STRICT),
        ) as PlanningDecision.Planned
        val unknown = BackendRecord(
            "order-1",
            me.ahoo.wow.query.backend.NormalizedValue.ObjectValue(emptyMap()),
            BackendRecordCompleteness.UNKNOWN,
        )
        val registration = QueryBackendRegistration(
            QueryBackendDescriptor(
                QueryBackendKey(PlanningFixtures.target, BackendId("probe")),
                PlanningFixtures.schema.contractId,
                setOf(QueryOperation.SINGLE),
                setOf(SemanticTier.PORTABLE),
                PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
            ),
            recordBackend = object : StubRecordBackend({ Mono.just(1) }) {
                override fun single(
                    plan: SingleQueryPlan,
                    options: QueryExecutionOptions,
                ): Mono<BackendRecord> = Mono.just(unknown)
            },
        )
        assertThrownBy<QueryRejectedException> {
            QueryExecutor(deadlineEnforcer).single(
                QueryExecutionRoute.Planned(
                    QueryBackendRegistry(
                        listOf(registration),
                        mapOf(PlanningFixtures.target to BackendId("probe")),
                    ),
                    singleDecision.plan,
                ),
                options,
            ).block()
        }.satisfies(
            java.util.function.Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.INCOMPLETE_RESULT)
                error.rejection.code.assert().isEqualTo(QueryRejectionCode.INCOMPLETE_RESULT)
            },
        )

        val legacy = legacyBinding(singleAction = { Mono.just(unknown) })
        QueryExecutor(deadlineEnforcer).single(
            QueryExecutionRoute.Legacy(
                legacy,
                LegacyCompilationInput(singleInvocation, PlanningFixtures.schema, singleDecision),
            ),
            options,
        ).block().assert().isEqualTo(unknown)
    }

    private fun shadowRoute(
        plannedBackend: RecordQueryBackend,
        legacyCount: () -> Mono<Long>,
    ): QueryExecutionRoute.Shadow {
        val registration = QueryBackendRegistration(
            QueryBackendDescriptor(
                QueryBackendKey(PlanningFixtures.target, BackendId("probe")),
                PlanningFixtures.schema.contractId,
                setOf(QueryOperation.COUNT),
                setOf(SemanticTier.PORTABLE),
                PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
            ),
            recordBackend = plannedBackend,
        )
        val legacy = legacyBinding(countAction = { Mono.defer(legacyCount) })
        return QueryExecutionRoute.Shadow(
            legacy,
            LegacyCompilationInput(invocation, PlanningFixtures.schema, decision),
            QueryBackendRegistry(
                listOf(registration),
                mapOf(PlanningFixtures.target to BackendId("probe")),
            ),
            plan,
        )
    }

    private open class StubRecordBackend(
        private val countAction: (CountQueryPlan) -> Mono<Long>,
    ) : RecordQueryBackend {
        override fun single(plan: SingleQueryPlan, options: QueryExecutionOptions): Mono<BackendRecord> = Mono.empty()

        override fun stream(plan: StreamQueryPlan, options: QueryExecutionOptions): Flux<BackendRecord> = Flux.empty()

        override fun page(plan: PageQueryPlan, options: QueryExecutionOptions): Mono<BackendPage> = Mono.empty()

        override fun count(plan: CountQueryPlan, options: QueryExecutionOptions): Mono<Long> = countAction(plan)
    }

    private fun legacyBinding(
        singleAction: () -> Mono<BackendRecord> = { Mono.empty() },
        streamAction: () -> Flux<BackendRecord> = { Flux.empty() },
        pageAction: () -> Mono<BackendPage> = { Mono.empty() },
        countAction: () -> Mono<Long> = { Mono.empty() },
    ): LegacyExecutionBinding = LegacyExecutionBinding.create(
        PlanningFixtures.target,
        LegacyQueryCompiler { input ->
            TestCompiledQuery(
                input.invocation.target,
                input.invocation.operation,
                input.schema.contractId,
                input.attestLowering(
                    input.enforcementRequirements.deletionScope,
                    input.enforcementRequirements.mandatoryCondition,
                ),
            )
        },
        object : LegacyQueryBackend<TestCompiledQuery> {
            override fun single(query: TestCompiledQuery, options: QueryExecutionOptions): Mono<BackendRecord> =
                singleAction()

            override fun stream(query: TestCompiledQuery, options: QueryExecutionOptions): Flux<BackendRecord> =
                streamAction()

            override fun page(query: TestCompiledQuery, options: QueryExecutionOptions): Mono<BackendPage> = pageAction()

            override fun count(query: TestCompiledQuery, options: QueryExecutionOptions): Mono<Long> = countAction()
        },
    )

    private data class TestCompiledQuery(
        override val target: me.ahoo.wow.query.internal.model.QueryTarget,
        override val operation: QueryOperation,
        override val schemaContractId: SchemaContractId,
        override val loweringAttestation: LegacyLoweringAttestation,
    ) : LegacyCompiledQuery
}
