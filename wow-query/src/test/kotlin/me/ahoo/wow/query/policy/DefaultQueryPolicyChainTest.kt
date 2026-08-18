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

package me.ahoo.wow.query.policy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryExpressionValidator
import me.ahoo.wow.query.validation.QueryStructureLimits
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class DefaultQueryPolicyChainTest {
    @Test
    fun `combines expressions fields budgets and capabilities independently of registration order`() {
        val context = context(
            expression = fullText(),
            requestBudget = QueryBudgetHint(maxResults = 500, maxCost = 500)
        )
        val observedContexts = mutableListOf<QueryPolicyContext>()
        val first = descriptor("first", 20) {
            observedContexts += it
            Mono.just(
                QueryPolicyResult(
                    mandatoryExpression = predicate(STATUS, "OPEN"),
                    constraints = QueryPolicyConstraints(
                        fieldAccess = QueryFieldAccess.Restricted(setOf(STATUS, TENANT)),
                        capabilityAccess = mapOf(FULL_TEXT to CapabilityDecision.GRANT),
                        maxBudget = QueryBudgetLimit(Duration.ofSeconds(20), 100, 70)
                    )
                )
            )
        }
        val second = descriptor("second", 10) {
            observedContexts += it
            Mono.just(
                QueryPolicyResult(
                    mandatoryExpression = predicate(TENANT, "tenant"),
                    constraints = QueryPolicyConstraints(
                        fieldAccess = QueryFieldAccess.Restricted(setOf(STATUS, OWNER)),
                        capabilityAccess = mapOf(FULL_TEXT to CapabilityDecision.ABSTAIN),
                        maxBudget = QueryBudgetLimit(Duration.ofSeconds(10), 200, 50)
                    )
                )
            )
        }

        val forward = chain(listOf(first, second)).evaluate(context).block()!!
        observedContexts.assert().hasSize(2)
        observedContexts.all { it === context }.assert().isTrue()
        observedContexts.clear()
        val reverse = chain(listOf(second, first)).evaluate(context).block()!!

        reverse.assert().isEqualTo(forward)
        observedContexts.all { it === context }.assert().isTrue()
        forward.constraints.fieldAccess.assert().isEqualTo(QueryFieldAccess.Restricted(setOf(STATUS)))
        forward.constraints.maxBudget.assert().isEqualTo(
            QueryBudgetLimit(Duration.ofSeconds(10), 100, 50)
        )
        forward.constraints.capabilityAccess.assert().isEqualTo(mapOf(FULL_TEXT to CapabilityDecision.GRANT))
        forward.securedExpression.toString().contains("deleted").assert().isTrue()
        forward.securedExpression.toString().contains("OPEN").assert().isTrue()
        forward.securedExpression.toString().contains("tenant").assert().isTrue()
    }

    @Test
    fun `deny wins and all abstain deny requested capabilities while one grant allows`() {
        val context = context(expression = fullText())

        assertPolicyError(
            chain(
                listOf(
                    descriptor("grant") { Mono.just(result(FULL_TEXT, CapabilityDecision.GRANT)) },
                    descriptor("deny") { Mono.just(result(FULL_TEXT, CapabilityDecision.DENY)) }
                )
            ).evaluate(context),
            QueryErrorCode.POLICY_DENIED,
            QueryErrorReason.CAPABILITY_DENIED
        )
        assertPolicyError(
            chain(listOf(descriptor("abstain") { Mono.just(result(FULL_TEXT, CapabilityDecision.ABSTAIN)) }))
                .evaluate(context),
            QueryErrorCode.POLICY_DENIED,
            QueryErrorReason.CAPABILITY_DENIED
        )

        val granted = chain(
            listOf(descriptor("grant") { Mono.just(result(FULL_TEXT, CapabilityDecision.GRANT)) })
        ).evaluate(context).block()!!
        granted.constraints.capabilityAccess[FULL_TEXT].assert().isEqualTo(CapabilityDecision.GRANT)
    }

    @Test
    fun `maps denied empty unexpected and invalid outputs without invoking downstream resolver`() {
        val secret = "secret-sentinel-4729"
        val failures = listOf(
            descriptor("denied") { Mono.error(QueryPolicyDeniedException("TENANT_REQUIRED")) } to
                (QueryErrorCode.POLICY_DENIED to QueryErrorReason.POLICY_EVALUATION_FAILED),
            descriptor("empty") { Mono.empty() } to
                (QueryErrorCode.POLICY_FAILURE to QueryErrorReason.POLICY_EVALUATION_FAILED),
            descriptor("unexpected") { Mono.error(IllegalStateException(secret)) } to
                (QueryErrorCode.POLICY_FAILURE to QueryErrorReason.POLICY_EVALUATION_FAILED),
            descriptor("spoofed-framework-error") {
                Mono.error(
                    QueryException(
                        QueryErrorCode.POLICY_DENIED,
                        QueryStage.POLICY,
                        QueryErrorReason.CAPABILITY_DENIED
                    )
                )
            } to (QueryErrorCode.POLICY_FAILURE to QueryErrorReason.POLICY_EVALUATION_FAILED),
            descriptor("invalid") {
                Mono.just(QueryPolicyResult(mandatoryExpression = predicate(LogicalField("state.unknown"), secret)))
            } to (QueryErrorCode.POLICY_FAILURE to QueryErrorReason.POLICY_EVALUATION_FAILED)
        )

        failures.forEach { (policy, expected) ->
            val resolverCalls = AtomicInteger()
            StepVerifier.create(
                chain(listOf(policy)).evaluate(context()).flatMap {
                    resolverCalls.incrementAndGet()
                    Mono.just(it)
                }
            ).expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(expected.first)
                    stage.assert().isEqualTo(QueryStage.POLICY)
                    reason.assert().isEqualTo(expected.second)
                    message.orEmpty().contains(secret).assert().isFalse()
                    message.orEmpty().contains(policy.descriptorId).assert().isFalse()
                }
            }.verify()
            resolverCalls.get().assert().isZero()
        }
    }

    @Test
    fun `expires immediate deadline before evaluating policies or downstream resolver`() {
        val policyCalls = AtomicInteger()
        val resolverCalls = AtomicInteger()
        val context = context(requestBudget = QueryBudgetHint(timeout = Duration.ZERO))
        val policy = descriptor("never") {
            policyCalls.incrementAndGet()
            Mono.never()
        }

        StepVerifier.create(
            chain(listOf(policy)).evaluate(context).flatMap {
                resolverCalls.incrementAndGet()
                Mono.just(it)
            }
        ).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
                stage.assert().isEqualTo(QueryStage.POLICY)
                reason.assert().isEqualTo(QueryErrorReason.DEADLINE_REACHED)
            }
        }.verify()

        policyCalls.get().assert().isZero()
        resolverCalls.get().assert().isZero()
    }

    @Test
    fun `times out in flight policy without invoking downstream resolver`() {
        val scheduler = VirtualTimeScheduler.create()
        val policyCalls = AtomicInteger()
        val resolverCalls = AtomicInteger()
        val context = context(requestBudget = QueryBudgetHint(timeout = Duration.ofSeconds(1)))
        val policy = descriptor("never") {
            policyCalls.incrementAndGet()
            Mono.never()
        }
        val chain = DefaultQueryPolicyChain(
            systemPolicy = SystemQueryPolicy(QueryBudgetLimit(Duration.ofSeconds(30), 300, 300)),
            customPolicies = listOf(policy),
            expressionValidator = QueryExpressionValidator(LIMITS)
        )
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)

        StepVerifier.create(
            chain.evaluate(context, guard).flatMap {
                resolverCalls.incrementAndGet()
                Mono.just(it)
            }
        ).then {
            scheduler.advanceTimeBy(Duration.ofSeconds(1))
        }.expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
                stage.assert().isEqualTo(QueryStage.POLICY)
                reason.assert().isEqualTo(QueryErrorReason.DEADLINE_REACHED)
            }
        }.verify()

        policyCalls.get().assert().isEqualTo(1)
        resolverCalls.get().assert().isZero()
    }

    @Test
    fun `rejects an absolute deadline already past when policy subscription starts`() {
        val policyCalls = AtomicInteger()
        val absoluteDeadline = FROZEN.plusSeconds(1)
        val chain = chain(
            listOf(
                descriptor("never") {
                    policyCalls.incrementAndGet()
                    Mono.just(QueryPolicyResult())
                }
            )
        )
        val scheduler = VirtualTimeScheduler.create()
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        scheduler.advanceTimeBy(Duration.ofSeconds(1).plusNanos(1))

        assertPolicyError(
            chain.evaluate(context(), guard, absoluteDeadline),
            QueryErrorCode.DEADLINE_EXCEEDED,
            QueryErrorReason.DEADLINE_REACHED
        )
        policyCalls.get().assert().isZero()
    }

    @Test
    fun `uses only absolute deadline remaining time and cancels in flight policy`() {
        val scheduler = VirtualTimeScheduler.create()
        val absoluteDeadline = FROZEN.plusSeconds(1)
        val cancellationCalls = AtomicInteger()
        val chain = DefaultQueryPolicyChain(
            systemPolicy = SystemQueryPolicy(QueryBudgetLimit.UNBOUNDED),
            customPolicies = listOf(
                descriptor("never") {
                    Mono.never<QueryPolicyResult>().doOnCancel(cancellationCalls::incrementAndGet)
                }
            ),
            expressionValidator = QueryExpressionValidator(LIMITS)
        )
        val guard = QueryDeadlineGuard.anchor(FROZEN, scheduler)
        scheduler.advanceTimeBy(Duration.ofMillis(600))

        StepVerifier.withVirtualTime(
            { chain.evaluate(context(), guard, absoluteDeadline) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .expectNoEvent(Duration.ofMillis(399))
            .thenAwait(Duration.ofMillis(1))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
                    stage.assert().isEqualTo(QueryStage.POLICY)
                    reason.assert().isEqualTo(QueryErrorReason.DEADLINE_REACHED)
                }
            }.verify(Duration.ofSeconds(1))

        cancellationCalls.get().assert().isOne()
    }

    @Test
    fun `unbounded policy evaluation does not derive a deadline from frozen instant`() {
        val policyCalls = AtomicInteger()
        val chain = chain(
            listOf(
                descriptor("success") {
                    policyCalls.incrementAndGet()
                    Mono.just(QueryPolicyResult())
                }
            )
        )

        chain.evaluate(context(), deadlineGuard(), null).block()!!
            .securedExpression.toString().contains("deleted").assert().isTrue()
        policyCalls.get().assert().isOne()
    }

    @Test
    fun `policy chain has no wall clock or scheduler dependency`() {
        DefaultQueryPolicyChain::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
            .none { it == Clock::class.java || it == Scheduler::class.java }
            .assert().isTrue()
    }

    @Test
    fun `takes immutable snapshots and redacts policy values`() {
        val fields = linkedSetOf(STATUS)
        val capabilities = linkedMapOf(FULL_TEXT to CapabilityDecision.GRANT)
        val constraints = QueryPolicyConstraints(
            fieldAccess = QueryFieldAccess.Restricted(fields),
            capabilityAccess = capabilities
        )
        val secret = "secret-sentinel-9281"
        val result = QueryPolicyResult(predicate(STATUS, secret), constraints)
        val context = context(expression = predicate(STATUS, secret))

        fields += TENANT
        capabilities[FULL_TEXT] = CapabilityDecision.DENY

        (constraints.fieldAccess as QueryFieldAccess.Restricted).fields.assert().containsExactly(STATUS)
        constraints.capabilityAccess.assert().isEqualTo(mapOf(FULL_TEXT to CapabilityDecision.GRANT))
        listOf(result.toString(), constraints.toString(), context.toString()).forEach { rendered ->
            rendered.contains(secret).assert().isFalse()
        }
    }

    @Test
    fun `custom schema mutation by one policy cannot change what later policies observe`() {
        val target = target(QueryDocumentKind.SNAPSHOT)
        val mutableFields = QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT)
            .associateByTo(LinkedHashMap(), QueryFieldSchema::path)
        val mutableView = object : QuerySchemaView {
            override val target: QueryTarget = target
            override val fields: Map<LogicalField, QueryFieldSchema> = mutableFields
        }
        val context = context(schema = mutableView)
        val observedFieldCounts = mutableListOf<Int>()
        val mutator = descriptor("mutator", 0) {
            mutableFields.clear()
            Mono.just(QueryPolicyResult())
        }
        val observer = descriptor("observer", 1) {
            observedFieldCounts += it.schema.fields.size
            Mono.just(QueryPolicyResult())
        }

        chain(listOf(mutator, observer)).evaluate(context).block()

        mutableFields.assert().isEmpty()
        observedFieldCounts.assert().containsExactly(QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT).size)
    }

    @Test
    fun `invokes downstream resolver exactly once after successful combination`() {
        val resolverCalls = AtomicInteger()

        val result = chain(emptyList()).evaluate(context()).flatMap {
            resolverCalls.incrementAndGet()
            Mono.just(it)
        }.block()!!

        result.securedExpression.toString().contains("deleted").assert().isTrue()
        resolverCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `retains mandatory policy contribution when secured expression simplifies to match none`() {
        val secret = "mandatory-secret-3024"
        val mandatory = predicate(STATUS, secret)

        val result = chain(
            listOf(descriptor("mandatory") { Mono.just(QueryPolicyResult(mandatory)) })
        ).evaluate(context(expression = MatchNone)).block()!!

        result.securedExpression.assert().isSameAs(MatchNone)
        result.mandatoryExpression.toString().contains("deleted").assert().isTrue()
        result.mandatoryExpression.toString().contains(secret).assert().isTrue()
        result.toString().contains(secret).assert().isFalse()
    }

    private fun chain(customPolicies: List<QueryPolicyRegistration>): DefaultQueryPolicyChain =
        DefaultQueryPolicyChain(
            systemPolicy = SystemQueryPolicy(QueryBudgetLimit(Duration.ofSeconds(30), 300, 300)),
            customPolicies = customPolicies,
            expressionValidator = QueryExpressionValidator(LIMITS)
        )

    private fun DefaultQueryPolicyChain.evaluate(context: QueryPolicyContext): Mono<CombinedQueryPolicyResult> =
        evaluate(context, deadlineGuard())

    private fun deadlineGuard(): QueryDeadlineGuard =
        QueryDeadlineGuard.anchor(FROZEN, VirtualTimeScheduler.create())

    private fun result(capability: QueryCapabilityId, decision: CapabilityDecision): QueryPolicyResult =
        QueryPolicyResult(
            constraints = QueryPolicyConstraints(capabilityAccess = mapOf(capability to decision))
        )

    private fun descriptor(
        id: String,
        order: Int = 0,
        evaluate: (QueryPolicyContext) -> Mono<QueryPolicyResult>
    ): QueryPolicyRegistration = QueryPolicyRegistration(id, order, QueryPolicy(evaluate))

    private fun assertPolicyError(
        publisher: Mono<CombinedQueryPolicyResult>,
        code: QueryErrorCode,
        reason: QueryErrorReason
    ) {
        StepVerifier.create(publisher).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                this.code.assert().isEqualTo(code)
                stage.assert().isEqualTo(QueryStage.POLICY)
                this.reason.assert().isEqualTo(reason)
            }
        }.verify()
    }

    private fun context(
        expression: QueryExpression = MatchAll,
        deletion: DeletionScope = DeletionScope.DEFAULT,
        requestBudget: QueryBudgetHint = QueryBudgetHint(),
        permissions: Set<String> = emptySet(),
        schema: QuerySchemaView? = null
    ): QueryPolicyContext {
        val target = target(QueryDocumentKind.SNAPSHOT)
        val resolvedSchema = schema ?: QuerySchema(
            target,
            QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
                QueryFieldSchema(
                    path = STATUS,
                    valueKind = me.ahoo.wow.query.schema.QueryFieldValueKind.STRING,
                    nullable = false,
                    capabilities = setOf(FULL_TEXT),
                    bindings = setOf(
                        QueryCapabilityBinding(
                            QueryBackendId("test"),
                            QueryFieldUsage.SEARCH,
                            QueryBackendFieldPath("state.status")
                        )
                    )
                )
        )
        return QueryPolicyContext(
            target = target,
            operation = QueryOperation.COUNT,
            normalizedExpression = expression,
            resultShape = QueryPolicyResultShape.Count,
            invocationScope = QueryInvocationScope(
                QueryAuthorityView("subject", "tenant", null, emptySet(), permissions),
                RequestedQueryScope(deletion = deletion),
                "correlation"
            ),
            schema = resolvedSchema,
            requestBudget = requestBudget,
            frozenInstant = FROZEN,
            zoneId = ZoneOffset.UTC
        )
    }

    private fun fullText(): FullTextExpression = FullTextExpression(FULL_TEXT, "orders", setOf(STATUS))

    private fun predicate(field: LogicalField, value: String): PredicateExpression = PredicateExpression(
        field,
        PortableOperator.EQ,
        listOf(QueryValue.StringValue(value))
    )

    private fun target(kind: QueryDocumentKind): QueryTarget = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = "example"
            override val aggregateName: String = "order"
        },
        kind
    )

    private companion object {
        val FROZEN: Instant = Instant.parse("2026-08-12T08:00:00Z")
        val STATUS: LogicalField = LogicalField("state.status")
        val TENANT: LogicalField = LogicalField("tenantId")
        val OWNER: LogicalField = LogicalField("ownerId")
        val FULL_TEXT: QueryCapabilityId = QueryCapabilityId("full-text")
        val LIMITS: QueryStructureLimits = QueryStructureLimits(64, 10_000, 10_000, 1_048_576)
    }
}
