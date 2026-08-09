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

import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.SchemaContractId
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.plan.AnalyticsQueryPlan
import me.ahoo.wow.query.internal.plan.CountQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.RecordQueryPlan
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Immutable input to a target-specific legacy compiler. It never contains the original wire DTO.
 */
internal data class LegacyCompilationInput(
    val invocation: NormalizedQueryInvocation,
    val schema: QueryDocumentSchema,
    val decision: PlanningDecision,
) {
    private val sourceToken = LegacyCompilationToken()

    val enforcementRequirements: LegacyEnforcementRequirements = LegacyEnforcementRequirements(
        deletionScope = invocation.deletionScope(),
        mandatoryCondition = decision.mandatoryCondition(),
    )

    init {
        require(invocation.target == schema.target) {
            "Legacy compilation target must match the schema target."
        }
        require(invocation.hasConsistentShape()) {
            "Legacy compilation invocation has an inconsistent operation or result shape."
        }
        when (decision) {
            is PlanningDecision.Planned -> require(
                decision.plan.target == invocation.target &&
                    decision.plan.schemaContractId == schema.contractId &&
                    decision.plan.operation == invocation.operation &&
                    decision.plan.matchesResultShape(invocation.resultShape),
            ) {
                "Planned legacy compilation proof must match invocation, schema, operation and result shape."
            }

            is PlanningDecision.LegacyFallback -> require(
                decision.validatedMandatory.target == invocation.target &&
                    decision.validatedMandatory.schemaContractId == schema.contractId,
            ) {
                "Legacy fallback mandatory proof must match invocation and schema."
            }
        }
    }

    /**
     * Creates a trusted compiler attestation that both the framework deletion rule and the validated mandatory
     * condition were lowered. It is not an independent inspection of a physical backend query. The per-input token
     * prevents a compiled query from being cached and replayed for another request.
     */
    fun attestLowering(
        deletionScope: NormalizedDeletionScope,
        mandatoryCondition: PlannedCondition,
    ): LegacyLoweringAttestation {
        val attested = LegacyEnforcementRequirements(deletionScope, mandatoryCondition)
        if (attested != enforcementRequirements) {
            rejectLegacyMandatory()
        }
        return LegacyLoweringAttestation(sourceToken, attested)
    }

    internal fun accepts(attestation: LegacyLoweringAttestation): Boolean =
        attestation.sourceToken === sourceToken && attestation.requirements == enforcementRequirements
}

internal data class LegacyEnforcementRequirements(
    val deletionScope: NormalizedDeletionScope,
    val mandatoryCondition: PlannedCondition,
)

internal class LegacyLoweringAttestation internal constructor(
    internal val sourceToken: LegacyCompilationToken,
    internal val requirements: LegacyEnforcementRequirements,
)

internal class LegacyCompilationToken

internal interface LegacyCompiledQuery {
    val target: QueryTarget
    val operation: QueryOperation
    val schemaContractId: SchemaContractId
    val loweringAttestation: LegacyLoweringAttestation
}

internal fun interface LegacyQueryCompiler<C : LegacyCompiledQuery> {
    fun compile(input: LegacyCompilationInput): C
}

internal interface LegacyQueryBackend<C : LegacyCompiledQuery> {
    fun single(query: C, options: QueryExecutionOptions): Mono<BackendRecord>

    fun stream(query: C, options: QueryExecutionOptions): Flux<BackendRecord>

    fun page(query: C, options: QueryExecutionOptions): Mono<BackendPage>

    fun count(query: C, options: QueryExecutionOptions): Mono<Long>
}

/**
 * Erased final binding constructed only from the typed compiler/backend pair below. Registry callers cannot provide an
 * alternate implementation that bypasses per-input lowering attestation validation.
 */
internal class LegacyExecutionBinding private constructor(
    val target: QueryTarget,
    private val delegate: LegacyExecutionDelegate,
) {
    fun single(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendRecord> =
        delegate.single(input, options)

    fun stream(input: LegacyCompilationInput, options: QueryExecutionOptions): Flux<BackendRecord> =
        delegate.stream(input, options)

    fun page(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendPage> =
        delegate.page(input, options)

    fun count(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<Long> =
        delegate.count(input, options)

    companion object {
        fun <C : LegacyCompiledQuery> create(
            target: QueryTarget,
            compiler: LegacyQueryCompiler<C>,
            backend: LegacyQueryBackend<C>,
            errorBoundary: QueryErrorBoundary = QueryErrorBoundary(),
        ): LegacyExecutionBinding = LegacyExecutionBinding(
            target,
            DefaultLegacyExecutionDelegate(target, compiler, backend, errorBoundary),
        )
    }
}

private interface LegacyExecutionDelegate {
    fun single(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendRecord>

    fun stream(input: LegacyCompilationInput, options: QueryExecutionOptions): Flux<BackendRecord>

    fun page(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendPage>

    fun count(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<Long>
}

private class DefaultLegacyExecutionDelegate<C : LegacyCompiledQuery>(
    private val target: QueryTarget,
    private val compiler: LegacyQueryCompiler<C>,
    private val backend: LegacyQueryBackend<C>,
    private val errorBoundary: QueryErrorBoundary = QueryErrorBoundary(),
) : LegacyExecutionDelegate {
    override fun single(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendRecord> =
        Mono.defer {
            requireSupportedBudget(options)
            val compiled = compile(input, QueryOperation.SINGLE)
            backendMono { backend.single(compiled, options) }
        }

    override fun stream(input: LegacyCompilationInput, options: QueryExecutionOptions): Flux<BackendRecord> =
        Flux.defer {
            requireSupportedBudget(options)
            val compiled = compile(input, QueryOperation.STREAM)
            backendFlux { backend.stream(compiled, options) }
        }

    override fun page(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<BackendPage> =
        Mono.defer {
            requireSupportedBudget(options)
            val compiled = compile(input, QueryOperation.PAGE)
            backendMono { backend.page(compiled, options) }
        }

    override fun count(input: LegacyCompilationInput, options: QueryExecutionOptions): Mono<Long> =
        Mono.defer {
            requireSupportedBudget(options)
            val compiled = compile(input, QueryOperation.COUNT)
            backendMono { backend.count(compiled, options) }
        }

    private fun compile(input: LegacyCompilationInput, expectedOperation: QueryOperation): C {
        if (input.invocation.target != target || input.invocation.operation != expectedOperation) {
            rejectLegacy(QueryRejectionCode.LEGACY_LOWERING_UNSUPPORTED)
        }
        val compiled = compiler.compile(input)
        val matchesInput = compiled.target == target &&
            compiled.operation == expectedOperation &&
            compiled.schemaContractId == input.schema.contractId &&
            input.accepts(compiled.loweringAttestation)
        if (!matchesInput) {
            rejectLegacy(QueryRejectionCode.LEGACY_LOWERING_UNSUPPORTED)
        }
        return compiled
    }

    private fun requireSupportedBudget(options: QueryExecutionOptions) {
        val budget = options.budget
        val unsupported = sequenceOf(
            budget.maxScannedRecords,
            budget.maxCandidateBuckets,
            budget.maxReturnedBuckets,
            budget.maxCursorPages,
        ).any { value -> value != null } || budget.allowDiskUse
        if (unsupported) {
            rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                QueryRejectionPath.ROOT.property("executionContext").property("budget"),
                QueryRejectionCode.EXECUTION_BUDGET_UNSUPPORTED,
            )
        }
    }

    private fun <T : Any> backendMono(source: () -> Mono<T>): Mono<T> =
        Mono.defer(source).onErrorMap(errorBoundary::normalizeBackend)

    private fun <T : Any> backendFlux(source: () -> Flux<T>): Flux<T> =
        Flux.defer(source).onErrorMap(errorBoundary::normalizeBackend)
}

private fun NormalizedQueryInvocation.hasConsistentShape(): Boolean =
    when (operation) {
        QueryOperation.SINGLE -> input is NormalizedQueryInput.Single && resultShape.isRecord()
        QueryOperation.STREAM -> input is NormalizedQueryInput.Stream && resultShape.isRecord()
        QueryOperation.PAGE -> input is NormalizedQueryInput.Page && resultShape.isRecord()
        QueryOperation.COUNT -> input is NormalizedQueryInput.Count && resultShape == QueryResultShape.COUNT
        QueryOperation.ANALYZE -> input is NormalizedQueryInput.Analytics && resultShape == QueryResultShape.ANALYTICS
    }

private fun QueryResultShape.isRecord(): Boolean = this == QueryResultShape.TYPED || this == QueryResultShape.DYNAMIC

private fun QueryPlan.matchesResultShape(resultShape: QueryResultShape): Boolean =
    when (this) {
        is RecordQueryPlan -> this.resultShape.name == resultShape.name
        is CountQueryPlan -> resultShape == QueryResultShape.COUNT
        is AnalyticsQueryPlan -> resultShape == QueryResultShape.ANALYTICS
    }

private fun NormalizedQueryInvocation.deletionScope(): NormalizedDeletionScope =
    when (val normalizedInput = input) {
        is NormalizedQueryInput.Single -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Stream -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Page -> normalizedInput.query.deletionScope
        is NormalizedQueryInput.Count -> normalizedInput.deletionScope
        is NormalizedQueryInput.Analytics -> NormalizedDeletionScope.EXPLICIT
    }

private fun PlanningDecision.mandatoryCondition(): PlannedCondition =
    when (this) {
        is PlanningDecision.Planned -> plan.filter.mandatory
        is PlanningDecision.LegacyFallback -> validatedMandatory.condition
    }

internal class LegacyBackendRegistry(bindings: Iterable<LegacyExecutionBinding>) {
    val bindings: Map<QueryTarget, LegacyExecutionBinding>

    init {
        val bindingList = bindings.toList()
        require(bindingList.map(LegacyExecutionBinding::target).distinct().size == bindingList.size) {
            "Legacy backend targets must be unique."
        }
        val copy = LinkedHashMap<QueryTarget, LegacyExecutionBinding>(bindingList.size)
        bindingList.sortedWith(LEGACY_BINDING_COMPARATOR).forEach { binding -> copy[binding.target] = binding }
        this.bindings = Collections.unmodifiableMap(copy)
    }

    fun resolve(target: QueryTarget): LegacyExecutionBinding = bindings[target]
        ?: rejectQuery(
            QueryRejectionCategory.BACKEND_UNAVAILABLE,
            LEGACY_PATH,
            QueryRejectionCode.LEGACY_BACKEND_NOT_REGISTERED,
        )
}

internal fun rejectLegacy(
    code: QueryRejectionCode,
    category: QueryRejectionCategory = QueryRejectionCategory.UNSUPPORTED_FEATURE,
    cause: Throwable? = null,
): Nothing = rejectQuery(category, LEGACY_PATH, code, cause)

internal fun rejectLegacyMandatory(cause: Throwable? = null): Nothing = rejectQuery(
    QueryRejectionCategory.ACCESS_DENIED,
    QueryRejectionPath.ROOT.property("constraints").property("mandatoryCondition"),
    QueryRejectionCode.MANDATORY_CONDITION_UNENFORCEABLE,
    cause,
)

private val LEGACY_BINDING_COMPARATOR: Comparator<LegacyExecutionBinding> =
    compareBy<LegacyExecutionBinding> { binding -> binding.target.namedAggregate.contextName }
        .thenBy { binding -> binding.target.namedAggregate.aggregateName }
        .thenBy { binding -> binding.target.documentKind.name }

private val LEGACY_PATH = QueryRejectionPath.ROOT.property("legacy")
