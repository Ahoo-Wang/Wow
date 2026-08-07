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
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.policy.QueryExecutionBudget
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.schema.SchemaContractId
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class LegacyQueryExecutionTest {
    private val options = QueryExecutionOptions(null, QueryExecutionBudget())
    private val input = LegacyCompilationInput(
        PlanningFixtures.single(resultShape = me.ahoo.wow.query.internal.model.QueryResultShape.DYNAMIC),
        PlanningFixtures.schema,
        QueryPlanner().plan(
            PlanningFixtures.single(resultShape = me.ahoo.wow.query.internal.model.QueryResultShape.DYNAMIC),
            PlanningFixtures.schema,
            PlanningConstraints(QueryValidationMode.STRICT),
        ),
    )

    @Test
    fun `legacy compiler and backend should both remain cold and execute once per subscription`() {
        val compilerCalls = AtomicInteger()
        val backendCalls = AtomicInteger()
        val compiler = LegacyQueryCompiler<ProbeCompiledQuery> { compilation ->
            compilerCalls.incrementAndGet()
            ProbeCompiledQuery(
                compilation.invocation.target,
                compilation.invocation.operation,
                compilation.schema.contractId,
                compilation.attestLowering(
                    compilation.enforcementRequirements.deletionScope,
                    compilation.enforcementRequirements.mandatoryCondition,
                ),
            )
        }
        val backend = ProbeLegacyBackend {
            backendCalls.incrementAndGet()
            Mono.just(3)
        }
        val binding = LegacyExecutionBinding.create(PlanningFixtures.target, compiler, backend)

        val result = binding.count(countInput(), options)
        compilerCalls.get().assert().isZero()
        backendCalls.get().assert().isZero()
        result.block().assert().isEqualTo(3)
        result.block().assert().isEqualTo(3)
        compilerCalls.get().assert().isEqualTo(2)
        backendCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `legacy compiler mismatch and mandatory lowering failure should fail before backend`() {
        val backendCalls = AtomicInteger()
        val backend = ProbeLegacyBackend {
            backendCalls.incrementAndGet()
            Mono.just(3)
        }
        val otherTarget = QueryTarget(
            me.ahoo.wow.modeling.MaterializedNamedAggregate("sales", "other"),
            QueryDocumentKind.SNAPSHOT,
        )
        val mismatched = LegacyExecutionBinding.create(
            PlanningFixtures.target,
            LegacyQueryCompiler { compilation ->
                ProbeCompiledQuery(
                    otherTarget,
                    QueryOperation.COUNT,
                    PlanningFixtures.schema.contractId,
                    compilation.attestLowering(
                        compilation.enforcementRequirements.deletionScope,
                        compilation.enforcementRequirements.mandatoryCondition,
                    ),
                )
            },
            backend,
        )
        assertRejected(QueryRejectionCode.LEGACY_LOWERING_UNSUPPORTED) {
            mismatched.count(countInput(), options).block()
        }
        backendCalls.get().assert().isZero()

        listOf<LegacyQueryCompiler<ProbeCompiledQuery>>(
            LegacyQueryCompiler { compilation ->
                compilation.attestLowering(
                    me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope.DEFAULT_ACTIVE,
                    compilation.enforcementRequirements.mandatoryCondition,
                )
                error("unreachable")
            },
            LegacyQueryCompiler { compilation ->
                compilation.attestLowering(
                    compilation.enforcementRequirements.deletionScope,
                    me.ahoo.wow.query.internal.plan.PlannedCondition.None,
                )
                error("unreachable")
            },
        ).forEach { compiler ->
            val mandatoryFailure = LegacyExecutionBinding.create(PlanningFixtures.target, compiler, backend)
            assertRejected(
                QueryRejectionCode.MANDATORY_CONDITION_UNENFORCEABLE,
                QueryRejectionCategory.ACCESS_DENIED,
            ) {
                mandatoryFailure.count(countInput(), options).block()
            }.also { error -> error.rejection.path.toString().assert().isEqualTo("$.constraints.mandatoryCondition") }
        }
        backendCalls.get().assert().isZero()
    }

    @Test
    fun `legacy compiled query should be bound to exactly one immutable compilation input`() {
        val backendCalls = AtomicInteger()
        var cached: ProbeCompiledQuery? = null
        val binding = LegacyExecutionBinding.create(
            PlanningFixtures.target,
            LegacyQueryCompiler { compilation ->
                cached ?: ProbeCompiledQuery(
                    compilation.invocation.target,
                    compilation.invocation.operation,
                    compilation.schema.contractId,
                    compilation.attestLowering(
                        compilation.enforcementRequirements.deletionScope,
                        compilation.enforcementRequirements.mandatoryCondition,
                    ),
                ).also { compiled -> cached = compiled }
            },
            ProbeLegacyBackend {
                backendCalls.incrementAndGet()
                Mono.just(3)
            },
        )

        binding.count(countInput(), options).block().assert().isEqualTo(3)
        assertRejected(QueryRejectionCode.LEGACY_LOWERING_UNSUPPORTED) {
            binding.count(countInput(), options).block()
        }
        backendCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `legacy compilation input should expose normalized immutable state instead of raw DTO`() {
        LegacyCompilationInput::class.java.declaredFields.map { field -> field.type }.assert()
            .doesNotContain(me.ahoo.wow.query.internal.model.QueryInvocation::class.java)
            .doesNotContain(me.ahoo.wow.api.query.Condition::class.java)
            .doesNotContain(Any::class.java)
        input.invocation.input.assert()
            .isInstanceOf(me.ahoo.wow.query.internal.normalization.NormalizedQueryInput.Single::class.java)
        input.decision.assert().isInstanceOf(PlanningDecision.Planned::class.java)
    }

    private fun countInput(): LegacyCompilationInput {
        val invocation = me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.COUNT,
            me.ahoo.wow.query.internal.model.QueryResultShape.COUNT,
            me.ahoo.wow.query.internal.normalization.NormalizedQueryInput.Count(
                me.ahoo.wow.query.internal.normalization.NormalizedCondition.All,
                me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope.EXPLICIT,
            ),
        )
        return LegacyCompilationInput(
            invocation,
            PlanningFixtures.schema,
            QueryPlanner().plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ),
        )
    }

    private fun assertRejected(
        code: QueryRejectionCode,
        category: QueryRejectionCategory = QueryRejectionCategory.UNSUPPORTED_FEATURE,
        action: () -> Any?,
    ): QueryRejectedException {
        var captured: QueryRejectedException? = null
        assertThrownBy<QueryRejectedException> { action() }.satisfies(
            Consumer { error ->
                captured = error
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
            },
        )
        return checkNotNull(captured)
    }

    private data class ProbeCompiledQuery(
        override val target: QueryTarget,
        override val operation: QueryOperation,
        override val schemaContractId: SchemaContractId,
        override val loweringAttestation: LegacyLoweringAttestation,
    ) : LegacyCompiledQuery

    private class ProbeLegacyBackend(
        private val countAction: () -> Mono<Long>,
    ) : LegacyQueryBackend<ProbeCompiledQuery> {
        override fun single(
            query: ProbeCompiledQuery,
            options: QueryExecutionOptions,
        ): Mono<BackendRecord> = Mono.empty()

        override fun stream(
            query: ProbeCompiledQuery,
            options: QueryExecutionOptions,
        ): Flux<BackendRecord> = Flux.empty()

        override fun page(
            query: ProbeCompiledQuery,
            options: QueryExecutionOptions,
        ): Mono<BackendPage> = Mono.empty()

        override fun count(query: ProbeCompiledQuery, options: QueryExecutionOptions): Mono<Long> = countAction()
    }
}
