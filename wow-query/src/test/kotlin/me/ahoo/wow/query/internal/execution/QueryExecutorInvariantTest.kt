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
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.analytics.AnalyticsDimension
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsMissingPolicy
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
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
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

class QueryExecutorInvariantTest {
    private val planner = QueryPlanner()
    private val options = QueryExecutionOptions(null, QueryExecutionBudget())
    private val executor = QueryExecutor(QueryDeadlineEnforcer(Clock.systemUTC(), Schedulers.immediate()))
    private val record = BackendRecord(
        "order-1",
        NormalizedValue.ObjectValue(emptyMap()),
        BackendRecordCompleteness.COMPLETE,
    )

    @Test
    fun `page count and analytics must emit exactly one result`() {
        val countInput = countInput()
        val pageInput = pageInput(size = 1)
        val analyticsPlan = analyticsPlan(limit = 1)

        listOf(
            { executor.count(plannedRoute(countInput.decision, countBackend = { Mono.empty() }), options).block() },
            { executor.count(legacyRoute(countInput, countResult = Mono.empty()), options).block() },
            { executor.page(plannedRoute(pageInput.decision, pageBackend = { Mono.empty() }), options).block() },
            { executor.page(legacyRoute(pageInput, pageResult = Mono.empty()), options).block() },
            {
                executor.analyze(
                    plannedAnalyticsRoute(analyticsPlan) { Mono.empty() },
                    options,
                ).block()
            },
        ).forEach { action -> assertIncomplete(action) }
    }

    @Test
    fun `page and stream result envelopes must honor plan cardinality`() {
        val pageInput = pageInput(size = 1)
        val oversizedPage = BackendPage(
            listOf(record, record.copy(identity = "order-2")),
            2,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.SAME_INPUT,
        )
        assertIncomplete {
            executor.page(plannedRoute(pageInput.decision, pageBackend = { Mono.just(oversizedPage) }), options).block()
        }
        assertIncomplete {
            executor.page(legacyRoute(pageInput, pageResult = Mono.just(oversizedPage)), options).block()
        }

        val inconsistentTotal = BackendPage(
            listOf(record),
            0,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.SAME_INPUT,
        )
        assertIncomplete {
            executor.page(plannedRoute(pageInput.decision, pageBackend = { Mono.just(inconsistentTotal) }), options)
                .block()
        }

        val streamDecision = planner.plan(
            normalizedStream(limit = 1),
            PlanningFixtures.schema,
            PlanningConstraints(QueryValidationMode.STRICT),
        ) as PlanningDecision.Planned
        StepVerifier.create(
            executor.stream(
                plannedRoute(streamDecision, streamBackend = { Flux.just(record, record.copy(identity = "order-2")) }),
                options,
            ),
        )
            .expectNext(record)
            .expectErrorSatisfies(::assertIncompleteError)
            .verify()
    }

    @Test
    fun `analytics result envelope must honor plan consistency completeness and shape`() {
        val analyticsPlan = analyticsPlan(limit = 1)
        val bucket = BackendAnalyticsBucket(
            emptyMap(),
            mapOf(AnalyticsAlias("count") to NormalizedValue.Int64(1)),
        )
        assertIncomplete {
            executor.analyze(
                plannedAnalyticsRoute(analyticsPlan) {
                    Mono.just(
                        BackendAnalyticsPage(
                            listOf(bucket, bucket),
                            null,
                            AnalyticsConsistency.EVENTUAL,
                            AnalyticsCompleteness.EXACT,
                        ),
                    )
                },
                options,
            ).block()
        }

        val snapshotPlan = analyticsPlan(limit = 1, consistency = AnalyticsConsistency.SNAPSHOT)
        listOf(
            BackendAnalyticsPage(
                listOf(bucket),
                null,
                AnalyticsConsistency.EVENTUAL,
                AnalyticsCompleteness.EXACT,
            ),
            BackendAnalyticsPage(
                listOf(bucket),
                null,
                AnalyticsConsistency.SNAPSHOT,
                AnalyticsCompleteness.APPROXIMATE,
            ),
            BackendAnalyticsPage(
                listOf(bucket),
                listOf(NormalizedValue.Text("unexpected-global-cursor")),
                AnalyticsConsistency.SNAPSHOT,
                AnalyticsCompleteness.EXACT,
            ),
            BackendAnalyticsPage(
                listOf(bucket),
                emptyList(),
                AnalyticsConsistency.SNAPSHOT,
                AnalyticsCompleteness.EXACT,
            ),
        ).forEach { invalidPage ->
            assertIncomplete {
                executor.analyze(plannedAnalyticsRoute(snapshotPlan) { Mono.just(invalidPage) }, options).block()
            }
        }
    }

    @Test
    fun `negative count must be rejected in every execution mode`() {
        val countInput = countInput()
        assertIncomplete {
            executor.count(plannedRoute(countInput.decision, countBackend = { Mono.just(-1) }), options).block()
        }
        assertIncomplete {
            executor.count(legacyRoute(countInput, countResult = Mono.just(-1)), options).block()
        }
        val shadow = QueryExecutionRoute.Shadow(
            legacyRoute(countInput, countResult = Mono.just(-1)).binding,
            countInput,
            registry(countInput.decision, countBackend = { Mono.just(1) }),
            (countInput.decision as PlanningDecision.Planned).plan,
        )
        assertIncomplete { executor.count(shadow, options).block() }
    }

    @Test
    fun `grouped analytics must bind exact dimension aliases and cursor arity`() {
        val plan = groupedAnalyticsPlan()
        val dimensionAlias = AnalyticsAlias("amount")
        val metricAlias = AnalyticsAlias("count")
        val key = NormalizedValue.Decimal(java.math.BigDecimal.TEN)
        val validBucket = BackendAnalyticsBucket(
            mapOf(dimensionAlias to key),
            mapOf(metricAlias to NormalizedValue.Int64(1)),
        )
        val valid = BackendAnalyticsPage(
            listOf(validBucket),
            listOf(key),
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT,
        )
        executor.analyze(plannedAnalyticsRoute(plan) { Mono.just(valid) }, options).block()
            .assert().isEqualTo(valid)

        listOf(
            BackendAnalyticsPage(
                listOf(BackendAnalyticsBucket(emptyMap(), mapOf(metricAlias to NormalizedValue.Int64(1)))),
                listOf(key),
                AnalyticsConsistency.EVENTUAL,
                AnalyticsCompleteness.EXACT,
            ),
            BackendAnalyticsPage(
                listOf(validBucket),
                emptyList(),
                AnalyticsConsistency.EVENTUAL,
                AnalyticsCompleteness.EXACT,
            ),
            BackendAnalyticsPage(
                listOf(validBucket),
                listOf(key, key),
                AnalyticsConsistency.EVENTUAL,
                AnalyticsCompleteness.EXACT,
            ),
        ).forEach { invalid ->
            assertIncomplete { executor.analyze(plannedAnalyticsRoute(plan) { Mono.just(invalid) }, options).block() }
        }
    }

    @Test
    fun `exact same-input page should return precisely the remaining window`() {
        listOf(
            PageSuccessCase(
                index = 1,
                offset = 0,
                total = 3,
                records = listOf(record, record.copy(identity = "order-2"))
            ),
            PageSuccessCase(index = 2, offset = 2, total = 3, records = listOf(record)),
            PageSuccessCase(index = 3, offset = 4, total = 3, records = emptyList()),
        ).forEach { case ->
            val pageInput = pageInput(size = 2, index = case.index, offset = case.offset)
            val page = BackendPage(
                case.records,
                case.total,
                BackendTotalRelation.EXACT,
                BackendPageConsistency.SAME_INPUT,
            )
            executor.page(plannedRoute(pageInput.decision, pageBackend = { Mono.just(page) }), options).block()
                .assert().isEqualTo(page)
        }
    }

    @Test
    fun `unbounded shadow stream should skip probe without resolving planned registry`() {
        val invocation = normalizedStream(limit = 0)
        val input = compilationInput(invocation)
        val legacy = legacyRoute(input)
        val submitted = AtomicInteger()
        val skipped = AtomicReference<QueryShadowSkip>()
        val supervisor = object : QueryShadowSupervisor {
            override fun submit(task: QueryShadowTask): QueryShadowSubmission {
                submitted.incrementAndGet()
                return QueryShadowSubmission.Accepted(QueryShadowHandle.NONE)
            }

            override fun onSkipped(skip: QueryShadowSkip) {
                skipped.set(skip)
            }
        }
        val shadow = QueryExecutionRoute.Shadow(
            legacy.binding,
            input,
            QueryBackendRegistry(emptyList(), emptyMap()),
            (input.decision as PlanningDecision.Planned).plan,
        )

        QueryExecutor(
            QueryDeadlineEnforcer(Clock.systemUTC(), Schedulers.immediate()),
            shadowSupervisor = supervisor,
        ).stream(shadow, options).collectList().block().assert().isEmpty()
        submitted.get().assert().isZero()
        skipped.get().issues.first.code.assert().isEqualTo(QueryRejectionCode.SHADOW_PROBE_UNBOUNDED_STREAM)
    }

    @Test
    fun `compatible fallback should be observable in every execution mode and only shadow should report a skipped probe`() {
        val input = countInput()
        val issue = QueryRejection(
            QueryRejectionCategory.UNSUPPORTED_FEATURE,
            QueryRejectionPath.ROOT.property("input"),
            QueryRejectionCode.CAPABILITY_UNAVAILABLE,
        )
        val submitted = AtomicInteger()
        val skipped = mutableListOf<QueryShadowSkip>()
        val fallbacks = mutableListOf<QueryFallback>()
        val supervisor = object : QueryShadowSupervisor {
            override fun submit(task: QueryShadowTask): QueryShadowSubmission {
                submitted.incrementAndGet()
                return QueryShadowSubmission.Accepted(QueryShadowHandle.NONE)
            }

            override fun onSkipped(skip: QueryShadowSkip) {
                skipped += skip
            }
        }
        val observer = object : QueryDecisionObserver {
            override fun onFallback(fallback: QueryFallback) {
                fallbacks += fallback
            }
        }
        val executor = QueryExecutor(
            QueryDeadlineEnforcer(Clock.systemUTC(), Schedulers.immediate()),
            shadowSupervisor = supervisor,
            decisionObserver = observer,
        )

        QueryExecutionMode.entries.forEach { executionMode ->
            val route = legacyRoute(input, countResult = Mono.just(1L)).copy(
                fallback = QueryFallback(
                    input.invocation.target,
                    input.invocation.operation,
                    executionMode,
                    NonEmptyList.of(issue),
                ),
            )
            executor.count(route, options).block().assert().isEqualTo(1L)
        }

        submitted.get().assert().isZero()
        fallbacks.map(QueryFallback::executionMode).assert().containsExactly(*QueryExecutionMode.entries.toTypedArray())
        skipped.assert().hasSize(1)
        skipped.single().issues.assert().isEqualTo(NonEmptyList.of(issue))
    }

    private fun plannedRoute(
        decision: PlanningDecision,
        countBackend: (CountQueryPlan) -> Mono<Long> = { Mono.empty() },
        pageBackend: (PageQueryPlan) -> Mono<BackendPage> = { Mono.empty() },
        streamBackend: (StreamQueryPlan) -> Flux<BackendRecord> = { Flux.empty() },
    ): QueryExecutionRoute.Planned {
        val planned = decision as PlanningDecision.Planned
        return QueryExecutionRoute.Planned(
            registry(decision, countBackend, pageBackend, streamBackend),
            planned.plan,
        )
    }

    private fun registry(
        decision: PlanningDecision,
        countBackend: (CountQueryPlan) -> Mono<Long> = { Mono.empty() },
        pageBackend: (PageQueryPlan) -> Mono<BackendPage> = { Mono.empty() },
        streamBackend: (StreamQueryPlan) -> Flux<BackendRecord> = { Flux.empty() },
    ): QueryBackendRegistry {
        val planned = decision as PlanningDecision.Planned
        val backendId = BackendId("probe")
        val backend = object : RecordQueryBackend {
            override fun single(
                plan: SingleQueryPlan,
                options: QueryExecutionOptions
            ): Mono<BackendRecord> = Mono.empty()

            override fun stream(plan: StreamQueryPlan, options: QueryExecutionOptions): Flux<BackendRecord> =
                streamBackend(plan)

            override fun page(plan: PageQueryPlan, options: QueryExecutionOptions): Mono<BackendPage> = pageBackend(
                plan
            )

            override fun count(plan: CountQueryPlan, options: QueryExecutionOptions): Mono<Long> = countBackend(plan)
        }
        val descriptor = QueryBackendDescriptor(
            QueryBackendKey(PlanningFixtures.target, backendId),
            PlanningFixtures.schema.contractId,
            setOf(planned.plan.operation),
            setOf(SemanticTier.PORTABLE),
            PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
        )
        return QueryBackendRegistry(
            listOf(QueryBackendRegistration(descriptor, recordBackend = backend)),
            mapOf(PlanningFixtures.target to backendId),
        )
    }

    private fun plannedAnalyticsRoute(
        plan: AnalyticsQueryPlan,
        result: (AnalyticsQueryPlan) -> Mono<BackendAnalyticsPage>,
    ): QueryExecutionRoute.Planned {
        val backendId = BackendId("analytics")
        val descriptor = QueryBackendDescriptor(
            QueryBackendKey(PlanningFixtures.target, backendId),
            PlanningFixtures.schema.contractId,
            setOf(QueryOperation.ANALYZE),
            setOf(SemanticTier.PORTABLE),
            PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
        )
        return QueryExecutionRoute.Planned(
            QueryBackendRegistry(
                listOf(
                    QueryBackendRegistration(
                        descriptor,
                        analyticsBackend = AnalyticsQueryBackend { planned, _ -> result(planned) },
                    ),
                ),
                mapOf(PlanningFixtures.target to backendId),
            ),
            plan,
        )
    }

    private fun legacyRoute(
        input: LegacyCompilationInput,
        countResult: Mono<Long> = Mono.empty(),
        pageResult: Mono<BackendPage> = Mono.empty(),
    ): QueryExecutionRoute.Legacy = QueryExecutionRoute.Legacy(
        LegacyExecutionBinding.create(
            PlanningFixtures.target,
            LegacyQueryCompiler { compilation ->
                InvariantCompiledQuery(
                    compilation.invocation.target,
                    compilation.invocation.operation,
                    compilation.schema.contractId,
                    compilation.attestLowering(
                        compilation.enforcementRequirements.deletionScope,
                        compilation.enforcementRequirements.mandatoryCondition,
                    ),
                )
            },
            object : LegacyQueryBackend<InvariantCompiledQuery> {
                override fun single(
                    query: InvariantCompiledQuery,
                    options: QueryExecutionOptions,
                ): Mono<BackendRecord> = Mono.empty()

                override fun stream(
                    query: InvariantCompiledQuery,
                    options: QueryExecutionOptions,
                ): Flux<BackendRecord> = Flux.empty()

                override fun page(
                    query: InvariantCompiledQuery,
                    options: QueryExecutionOptions,
                ): Mono<BackendPage> = pageResult

                override fun count(query: InvariantCompiledQuery, options: QueryExecutionOptions): Mono<Long> =
                    countResult
            },
        ),
        input,
    )

    private data class InvariantCompiledQuery(
        override val target: me.ahoo.wow.query.internal.model.QueryTarget,
        override val operation: QueryOperation,
        override val schemaContractId: SchemaContractId,
        override val loweringAttestation: LegacyLoweringAttestation,
    ) : LegacyCompiledQuery

    private fun countInput(): LegacyCompilationInput {
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.COUNT,
            QueryResultShape.COUNT,
            NormalizedQueryInput.Count(
                NormalizedCondition.All,
                me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope.EXPLICIT,
            ),
        )
        return compilationInput(invocation)
    }

    private fun pageInput(
        size: Int,
        index: Int = 1,
        offset: Long = 0,
    ): LegacyCompilationInput = compilationInput(PlanningFixtures.page(size = size, index = index, offset = offset))

    private fun compilationInput(invocation: NormalizedQueryInvocation): LegacyCompilationInput = LegacyCompilationInput(
        invocation,
        PlanningFixtures.schema,
        planner.plan(invocation, PlanningFixtures.schema, PlanningConstraints(QueryValidationMode.STRICT)),
    )

    private fun normalizedStream(limit: Int): NormalizedQueryInvocation = NormalizedQueryInvocation(
        PlanningFixtures.target,
        QueryOperation.STREAM,
        QueryResultShape.TYPED,
        NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit),
    )

    private fun analyticsPlan(
        limit: Int,
        consistency: AnalyticsConsistency = AnalyticsConsistency.EVENTUAL,
    ): AnalyticsQueryPlan {
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.ANALYZE,
            QueryResultShape.ANALYTICS,
            NormalizedQueryInput.Analytics(
                AnalyticsQuery(
                    NormalizedCondition.All,
                    AnalyticsGrouping.Global,
                    NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
                    bucketWindow = me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow.First(limit),
                ),
            ),
        )
        val planned = (
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as AnalyticsQueryPlan
        if (consistency == planned.requiredConsistency) {
            return planned
        }
        return AnalyticsQueryPlan.create(
            planned.target,
            planned.schemaContractId,
            planned.filter,
            planned.grouping,
            planned.metrics,
            planned.having,
            planned.bucketOrder,
            planned.bucketWindow,
            planned.numericPolicy,
            consistency,
            planned.requiredCompleteness,
            planned.requiredCapabilities,
            planned.semanticTier,
        )
    }

    private fun groupedAnalyticsPlan(): AnalyticsQueryPlan {
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.ANALYZE,
            QueryResultShape.ANALYTICS,
            NormalizedQueryInput.Analytics(
                AnalyticsQuery(
                    NormalizedCondition.All,
                    AnalyticsGrouping.By(
                        NonEmptyList.of(
                            AnalyticsDimension(
                                AnalyticsAlias("amount"),
                                PlanningFixtures.path("state", "amount"),
                                AnalyticsMissingPolicy.EXCLUDE,
                            ),
                        ),
                    ),
                    NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
                    bucketWindow = me.ahoo.wow.query.internal.analytics.AnalyticsBucketWindow.First(1),
                ),
            ),
        )
        return (
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as AnalyticsQueryPlan
    }

    private fun assertIncomplete(action: () -> Any?) {
        assertThrownBy<QueryRejectedException> { action() }.satisfies(Consumer(::assertIncompleteError))
    }

    private fun assertIncompleteError(error: Throwable) {
        val rejected = error as QueryRejectedException
        rejected.rejection.category.assert().isEqualTo(QueryRejectionCategory.INCOMPLETE_RESULT)
        rejected.rejection.path.toString().assert().isEqualTo("$.backend.result")
        rejected.rejection.code.assert().isEqualTo(QueryRejectionCode.INCOMPLETE_RESULT)
    }

    private data class PageSuccessCase(
        val index: Int,
        val offset: Long,
        val total: Long,
        val records: List<BackendRecord>,
    )
}
