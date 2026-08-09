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
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryExecutionMode
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.planning.ValidatedMandatory
import me.ahoo.wow.query.internal.policy.QueryAuthority
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.policy.QueryExecutionContext
import me.ahoo.wow.query.internal.policy.QueryPurpose
import me.ahoo.wow.query.internal.policy.QueryResourceScope
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.function.Consumer

class QueryExecutionRouteResolverTest {
    private val backendId = BackendId("mongo")
    private val planned = QueryPlanner().plan(
        PlanningFixtures.single(resultShape = me.ahoo.wow.query.internal.model.QueryResultShape.DYNAMIC),
        PlanningFixtures.schema,
        PlanningConstraints(QueryValidationMode.STRICT),
    ) as PlanningDecision.Planned
    private val registration = QueryBackendRegistration(
        QueryBackendDescriptor(
            QueryBackendKey(PlanningFixtures.target, backendId),
            PlanningFixtures.schema.contractId,
            setOf(QueryOperation.SINGLE),
            setOf(SemanticTier.PORTABLE),
            PlanningFixtures.schema.fields.mapValues { entry -> entry.value.capabilities },
            PlanningFixtures.schema.searchScopes.keys,
        ),
        recordBackend = EmptyRecordBackend(),
    )
    private val legacy = emptyLegacyBinding()
    private val resolver = QueryExecutionRouteResolver(
        QueryBackendRegistry(listOf(registration), mapOf(PlanningFixtures.target to backendId)),
        LegacyBackendRegistry(listOf(legacy)),
    )

    @Test
    fun `resolver should implement the execution mode and compatibility matrix`() {
        resolve(QueryExecutionMode.LEGACY, QueryValidationMode.COMPATIBLE, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Legacy::class.java)
        resolve(QueryExecutionMode.LEGACY, QueryValidationMode.STRICT, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Legacy::class.java)
        resolve(QueryExecutionMode.SHADOW, QueryValidationMode.COMPATIBLE, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Shadow::class.java)
        resolve(QueryExecutionMode.SHADOW, QueryValidationMode.STRICT, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Shadow::class.java)
        resolve(QueryExecutionMode.PLANNED, QueryValidationMode.COMPATIBLE, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Planned::class.java)
        resolve(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT, planned).assert()
            .isInstanceOf(QueryExecutionRoute.Planned::class.java)

        QueryExecutionMode.entries.forEach { mode ->
            val route = resolve(mode, QueryValidationMode.COMPATIBLE, fallback()) as QueryExecutionRoute.Legacy
            route.fallback.assert().isNotNull()
            val fallback = checkNotNull(route.fallback)
            fallback.executionMode.assert().isEqualTo(mode)
            fallback.target.assert().isEqualTo(PlanningFixtures.target)
            fallback.operation.assert().isEqualTo(QueryOperation.SINGLE)
        }
    }

    @Test
    fun `strict fallback should be an invariant failure in every execution mode`() {
        QueryExecutionMode.entries.forEach { mode ->
            assertRejected(QueryRejectionCategory.INTERNAL_FAILURE, QueryRejectionCode.EXECUTION_DECISION_INVALID) {
                resolve(mode, QueryValidationMode.STRICT, fallback())
            }
        }
    }

    @Test
    fun `planned route failure should never become legacy fallback`() {
        val missingPlannedResolver = QueryExecutionRouteResolver(
            QueryBackendRegistry(emptyList(), emptyMap()),
            LegacyBackendRegistry(listOf(legacy)),
        )

        val missingPlanned = missingPlannedResolver.resolve(
            context(QueryExecutionMode.PLANNED, QueryValidationMode.COMPATIBLE),
            invocation(),
            PlanningFixtures.schema,
            planned,
        ) as QueryExecutionRoute.Planned
        assertRejected(QueryRejectionCategory.BACKEND_UNAVAILABLE, QueryRejectionCode.BACKEND_NOT_REGISTERED) {
            missingPlanned.registry.resolve(missingPlanned.plan)
        }
        missingPlannedResolver.resolve(
            context(QueryExecutionMode.SHADOW, QueryValidationMode.COMPATIBLE),
            invocation(),
            PlanningFixtures.schema,
            planned,
        ).assert().isInstanceOf(QueryExecutionRoute.Shadow::class.java)
    }

    @Test
    fun `analytics should never enter legacy or shadow execution`() {
        val analytics = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.ANALYZE,
            me.ahoo.wow.query.internal.model.QueryResultShape.ANALYTICS,
            NormalizedQueryInput.Analytics(
                AnalyticsQuery(
                    NormalizedCondition.All,
                    AnalyticsGrouping.Global,
                    NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
                ),
            ),
        )
        listOf(QueryExecutionMode.LEGACY, QueryExecutionMode.SHADOW, QueryExecutionMode.PLANNED).forEach { mode ->
            assertRejected(QueryRejectionCategory.UNSUPPORTED_FEATURE, QueryRejectionCode.EXECUTION_MODE_UNSUPPORTED) {
                resolver.resolve(
                    context(mode, QueryValidationMode.COMPATIBLE),
                    analytics,
                    PlanningFixtures.schema,
                    fallback(),
                )
            }
        }
    }

    private fun resolve(
        mode: QueryExecutionMode,
        validationMode: QueryValidationMode,
        decision: PlanningDecision,
    ): QueryExecutionRoute = resolver.resolve(
        context(mode, validationMode),
        invocation(),
        PlanningFixtures.schema,
        decision,
    )

    private fun invocation() = PlanningFixtures.single(
        resultShape = me.ahoo.wow.query.internal.model.QueryResultShape.DYNAMIC,
    )

    private fun fallback(): PlanningDecision.LegacyFallback = PlanningDecision.LegacyFallback(
        NonEmptyList.of(
            QueryRejection(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionPath.ROOT.property("input"),
                QueryRejectionCode.CAPABILITY_UNAVAILABLE,
            ),
        ),
        ValidatedMandatory(
            PlanningFixtures.target,
            PlanningFixtures.schema.contractId,
            me.ahoo.wow.query.internal.plan.PlannedCondition.All,
            RequiredCapabilities(),
            SemanticTier.PORTABLE,
        ),
    )

    private fun context(
        mode: QueryExecutionMode,
        validationMode: QueryValidationMode,
    ): QueryExecutionContext = QueryExecutionContext(
        PlanningFixtures.target,
        QueryPurpose("test"),
        QueryAuthority.System("test", "execution-route-test"),
        mode,
        validationMode,
        QueryResourceScope(),
        deadline = null,
        QueryExecutionBudget(),
    )

    private fun assertRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        action: () -> Any?,
    ) {
        assertThrownBy<QueryRejectedException> { action() }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
            },
        )
    }

    private fun emptyLegacyBinding(): LegacyExecutionBinding = LegacyExecutionBinding.create(
        PlanningFixtures.target,
        LegacyQueryCompiler { input ->
            RouteCompiledQuery(
                input.invocation.target,
                input.invocation.operation,
                input.schema.contractId,
                input.attestLowering(
                    input.enforcementRequirements.deletionScope,
                    input.enforcementRequirements.mandatoryCondition,
                ),
            )
        },
        object : LegacyQueryBackend<RouteCompiledQuery> {
            override fun single(
                query: RouteCompiledQuery,
                options: QueryExecutionOptions,
            ): Mono<BackendRecord> = Mono.empty()

            override fun stream(
                query: RouteCompiledQuery,
                options: QueryExecutionOptions,
            ): Flux<BackendRecord> = Flux.empty()

            override fun page(
                query: RouteCompiledQuery,
                options: QueryExecutionOptions,
            ): Mono<BackendPage> = Mono.empty()

            override fun count(query: RouteCompiledQuery, options: QueryExecutionOptions): Mono<Long> = Mono.empty()
        },
    )

    private data class RouteCompiledQuery(
        override val target: me.ahoo.wow.query.internal.model.QueryTarget,
        override val operation: QueryOperation,
        override val schemaContractId: SchemaContractId,
        override val loweringAttestation: LegacyLoweringAttestation,
    ) : LegacyCompiledQuery

    private class EmptyRecordBackend : RecordQueryBackend {
        override fun single(
            plan: me.ahoo.wow.query.internal.plan.SingleQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<BackendRecord> = Mono.empty()

        override fun stream(
            plan: me.ahoo.wow.query.internal.plan.StreamQueryPlan,
            options: QueryExecutionOptions,
        ): Flux<BackendRecord> = Flux.empty()

        override fun page(
            plan: me.ahoo.wow.query.internal.plan.PageQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<BackendPage> = Mono.empty()

        override fun count(
            plan: me.ahoo.wow.query.internal.plan.CountQueryPlan,
            options: QueryExecutionOptions,
        ): Mono<Long> = Mono.empty()
    }
}
