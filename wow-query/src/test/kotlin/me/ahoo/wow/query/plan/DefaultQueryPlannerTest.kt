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

package me.ahoo.wow.query.plan

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.CombinedQueryPolicyResult
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class DefaultQueryPlannerTest {
    @Test
    fun `creates an immutable versioned list plan with authorized fields provenance stable sort and minimum budget`() {
        val scheduler = VirtualTimeScheduler.create()
        val invocation = invocation(scheduler = scheduler)
        val descriptor = descriptor(
            maxBudget = QueryBudgetLimit(Duration.ofSeconds(12), 50, 70)
        )
        val backend = RecordingQueryBackend(descriptor)
        val resolved = ResolvedQueryBackend.resolve(backend, ROUTE).block()!!
        val policyResult = policyResult(
            maxBudget = QueryBudgetLimit(Duration.ofSeconds(10), 60, 80)
        )

        val plan = planner().plan(invocation, policyResult, resolved).block() as ListQueryPlanV1<*>

        plan.target.assert().isEqualTo(TARGET)
        plan.securedExpression.assert().isSameAs(policyResult.securedExpression)
        plan.authorizedResultShape.assert().isEqualTo(
            QueryPlanResultShape.Typed(String::class.java, setOf(STATUS))
        )
        plan.sort.assert().isEqualTo(
            listOf(
                QuerySort(STATUS, QuerySortDirection.ASC),
                QuerySort(AGGREGATE_ID, QuerySortDirection.ASC)
            )
        )
        plan.limit.assert().isEqualTo(10)
        plan.effectiveBudget.assert().isEqualTo(
            QueryBudgetLimit(Duration.ofSeconds(10), 50, 70)
        )
        plan.effectiveDeadline.assert().isEqualTo(FROZEN.plusSeconds(10))
        plan.correlationId.assert().isEqualTo("correlation")
        plan.routeIdentity.assert().isEqualTo(ROUTE)
        plan.expressionProvenance.assert().isEqualTo(
            mapOf(
                QueryProvenance.CALLER_REQUEST to invocation.normalizedExpression,
                QueryProvenance.MANDATORY_POLICY to policyResult.mandatoryExpression
            )
        )
        resolved.readinessSnapshot.assert().isSameAs(QueryBackendReadiness.Ready)
        backend.readinessSubscriptions.get().assert().isOne()

        assertThrows<UnsupportedOperationException> {
            (plan.sort as MutableList<QuerySort>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (plan.expressionProvenance as MutableMap<QueryProvenance, QueryExpression>).clear()
        }
        Modifier.isPublic(plan.javaClass.modifiers).assert().isFalse()
        plan.javaClass.declaredMethods.none { Modifier.isPublic(it.modifiers) && it.name == "copy" }.assert().isTrue()
        plan.toString().contains("OPEN").assert().isFalse()
    }

    @Test
    fun `freezes descriptor and resolved readiness snapshots and never rereads readiness during planning or execution`() {
        val documentKinds = linkedSetOf(QueryDocumentKind.SNAPSHOT)
        val versions = linkedSetOf(QueryPlanVersion.V1)
        val operators = LinkedHashSet(PortableOperator.entries)
        val capabilities = linkedSetOf<QueryCapabilityId>()
        val descriptor = QueryBackendDescriptor(
            backendId = BACKEND_ID,
            documentKinds = documentKinds,
            planVersions = versions,
            portableOperators = operators,
            capabilities = capabilities,
            maxBudget = QueryBudgetLimit.UNBOUNDED
        )
        documentKinds.clear()
        versions.clear()
        operators.clear()
        capabilities += FULL_TEXT
        val backend = RecordingQueryBackend(descriptor)
        val resolved = ResolvedQueryBackend.resolve(backend, ROUTE).block()!!

        val plan = planner().plan(invocation(), policyResult(), resolved).block()!!

        @Suppress("UNCHECKED_CAST")
        val listPlan = plan as ListQueryPlanV1<Any>
        backend.list(listPlan).collectList().block()

        descriptor.documentKinds.assert().isEqualTo(setOf(QueryDocumentKind.SNAPSHOT))
        descriptor.planVersions.assert().isEqualTo(setOf(QueryPlanVersion.V1))
        descriptor.portableOperators.assert().isEqualTo(PortableOperator.entries.toSet())
        descriptor.capabilities.assert().isEmpty()
        backend.readinessSubscriptions.get().assert().isOne()
    }

    @Test
    fun `fails closed when readiness is empty fails or reports not ready`() {
        val cases: List<Mono<QueryBackendReadiness>> = listOf(
            Mono.empty<QueryBackendReadiness>(),
            Mono.error(IllegalStateException("sensitive readiness failure")),
            Mono.just<QueryBackendReadiness>(
                QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)
            )
        )

        cases.forEach { readiness ->
            val backend = RecordingQueryBackend(descriptor(), readiness)
            val publisher = ResolvedQueryBackend.resolve(backend, ROUTE).flatMap { resolved ->
                planner().plan(invocation(), policyResult(), resolved)
            }

            StepVerifier.create(publisher).expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
                    reason.assert().isEqualTo(QueryErrorReason.BACKEND_UNAVAILABLE)
                    (stage == QueryStage.BACKEND_RESOLUTION || stage == QueryStage.PLANNING).assert().isTrue()
                    message.orEmpty().contains("sensitive").assert().isFalse()
                }
            }.verify()
            backend.readinessSubscriptions.get().assert().isOne()
        }
    }

    @Test
    fun `rejects descriptor document version portable operator and capability mismatches before execution`() {
        val portableInvocation = invocation()
        val capabilityInvocation = invocation(
            expression = FullTextExpression(FULL_TEXT, "sensitive full text", setOf(STATUS))
        )
        val cases = listOf(
            Triple(portableInvocation, descriptor(documentKinds = setOf(QueryDocumentKind.EVENT_STREAM)), emptySet()),
            Triple(portableInvocation, descriptor(planVersions = setOf(QueryPlanVersion(2))), emptySet()),
            Triple(
                portableInvocation,
                descriptor(portableOperators = PortableOperator.entries.toSet() - PortableOperator.EQ),
                emptySet()
            ),
            Triple(capabilityInvocation, descriptor(capabilities = emptySet()), setOf(FULL_TEXT)),
            Triple(capabilityInvocation, descriptor(capabilities = setOf(FULL_TEXT)), emptySet())
        )

        cases.forEachIndexed { index, (invocation, descriptor, enabledCapabilities) ->
            val backend = RecordingQueryBackend(descriptor)
            val resolved = ResolvedQueryBackend.resolve(backend, ROUTE).block()!!
            val result = if (invocation === capabilityInvocation) {
                policyResult(
                    securedExpression = invocation.normalizedExpression,
                    capabilityAccess = mapOf(FULL_TEXT to CapabilityDecision.GRANT)
                )
            } else {
                policyResult(securedExpression = invocation.normalizedExpression)
            }

            StepVerifier.create(planner(enabledCapabilities).plan(invocation, result, resolved))
                .expectErrorSatisfies { error ->
                    (error as QueryException).apply {
                        if (index < 2) {
                            code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
                        } else {
                            code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
                        }
                        stage.assert().isEqualTo(QueryStage.PLANNING)
                    }
                }.verify()
            backend.listSubscriptions.get().assert().isZero()
        }
    }

    @Test
    fun `binds element match child fields relative to the object collection path`() {
        val expression = ElementMatchExpression(
            LINES,
            predicate(LogicalField("sku"), "sku-1")
        )
        val nestedSchema = QuerySchema(
            TARGET,
            QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
                QueryFieldSchema.string(STATUS, nullable = false).copy(sortable = true) +
                QueryFieldSchema(
                    path = LINES,
                    valueKind = QueryFieldValueKind.OBJECT,
                    nullable = false,
                    collectionKind = QueryCollectionKind.OBJECT,
                    elementMatchEnabled = true
                ) +
                QueryFieldSchema.string(LINE_SKU, nullable = false)
        )
        val invocation = invocation(expression = expression, schema = nestedSchema)
        val resolved = ResolvedQueryBackend.resolve(RecordingQueryBackend(descriptor()), ROUTE).block()!!

        planner().plan(
            invocation,
            policyResult(
                securedExpression = expression,
                fieldAccess = QueryFieldAccess.Restricted(nestedSchema.fields.keys)
            ),
            resolved
        ).block().assert().isNotNull()
    }

    @Test
    fun `rejects duplicate sort field conflicts unauthorized projection and insufficient result budget`() {
        val duplicateSort = invocation(
            sort = listOf(
                QuerySort(STATUS, QuerySortDirection.ASC),
                QuerySort(STATUS, QuerySortDirection.ASC)
            )
        )
        val conflictingSort = invocation(
            sort = listOf(
                QuerySort(STATUS, QuerySortDirection.ASC),
                QuerySort(STATUS, QuerySortDirection.DESC)
            )
        )
        val resolved = ResolvedQueryBackend.resolve(RecordingQueryBackend(descriptor()), ROUTE).block()!!

        listOf(duplicateSort, conflictingSort).forEach { invocation ->
            assertQueryError(
                planner().plan(invocation, policyResult(), resolved),
                QueryErrorCode.INVALID_QUERY,
                QueryErrorReason.INVALID_REQUEST
            )
        }
        assertQueryError(
            planner().plan(
                invocation(),
                policyResult(fieldAccess = QueryFieldAccess.Restricted(setOf(AGGREGATE_ID))),
                resolved
            ),
            QueryErrorCode.POLICY_DENIED,
            QueryErrorReason.FIELD_ACCESS_DENIED
        )
        assertQueryError(
            planner().plan(
                invocation(),
                policyResult(maxBudget = QueryBudgetLimit(maxResults = 5)),
                resolved
            ),
            QueryErrorCode.BUDGET_EXCEEDED,
            QueryErrorReason.BUDGET_LIMIT_REACHED
        )
    }

    @Test
    fun `uses the invocation monotonic deadline guard for a tighter backend deadline`() {
        val scheduler = VirtualTimeScheduler.create()
        val invocation = invocation(scheduler = scheduler)
        val backend = RecordingQueryBackend(
            descriptor(maxBudget = QueryBudgetLimit(timeout = Duration.ofSeconds(1)))
        )
        val resolved = ResolvedQueryBackend.resolve(backend, ROUTE).block()!!
        scheduler.advanceTimeBy(Duration.ofSeconds(1))

        assertQueryError(
            planner().plan(invocation, policyResult(), resolved),
            QueryErrorCode.DEADLINE_EXCEEDED,
            QueryErrorReason.DEADLINE_REACHED
        )
    }

    @Test
    fun `rejects a resolver snapshot whose descriptor differs from the backend descriptor`() {
        val backend = RecordingQueryBackend(descriptor())

        assertThrows<IllegalArgumentException> {
            ResolvedQueryBackend(
                backend = backend,
                descriptor = descriptor(maxBudget = QueryBudgetLimit(maxCost = 1)),
                routeIdentity = ROUTE,
                readinessSnapshot = QueryBackendReadiness.Ready
            )
        }
    }

    private fun assertQueryError(
        publisher: Mono<out QueryPlanV1>,
        code: QueryErrorCode,
        reason: QueryErrorReason
    ) {
        StepVerifier.create(publisher).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                this.code.assert().isEqualTo(code)
                stage.assert().isEqualTo(QueryStage.PLANNING)
                this.reason.assert().isEqualTo(reason)
            }
        }.verify()
    }

    private fun planner(enabledCapabilities: Set<QueryCapabilityId> = emptySet()): DefaultQueryPlanner =
        DefaultQueryPlanner(enabledCapabilities, QueryPlanValidator())

    private fun invocation(
        scheduler: VirtualTimeScheduler = VirtualTimeScheduler.create(),
        expression: QueryExpression = predicate(STATUS, "OPEN"),
        sort: List<QuerySort> = listOf(QuerySort(STATUS, QuerySortDirection.ASC)),
        schema: QuerySchema = schema(expression is FullTextExpression)
    ): QueryInvocation {
        val request = ListQueryRequest(
            target = TARGET,
            expression = expression,
            resultShape = QueryResultShape.Typed(
                String::class.java,
                QueryProjection.Include(setOf(STATUS))
            ),
            requestedScope = RequestedQueryScope(),
            budget = QueryBudgetHint(Duration.ofSeconds(20), 80, 90),
            sort = sort,
            limit = 10
        )
        return QueryInvocation(
            request = request,
            operation = QueryOperation.LIST,
            scope = QueryInvocationScope(
                QueryAuthorityView(null, null, null, emptySet(), emptySet()),
                request.requestedScope,
                "correlation"
            ),
            frozenInstant = FROZEN,
            zoneId = ZoneOffset.UTC,
            admissionDeadline = FROZEN.plusSeconds(15),
            admissionBudget = QueryBudgetLimit(Duration.ofSeconds(15), 70, 90),
            deadlineGuard = QueryDeadlineGuard.anchor(FROZEN, scheduler),
            schema = schema,
            normalizedExpression = expression,
            expressionProvenance = mapOf(QueryProvenance.CALLER_REQUEST to expression)
        )
    }

    private fun policyResult(
        securedExpression: QueryExpression = predicate(STATUS, "OPEN"),
        maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
        fieldAccess: QueryFieldAccess = QueryFieldAccess.Restricted(schema().fields.keys),
        capabilityAccess: Map<QueryCapabilityId, CapabilityDecision> = emptyMap()
    ): CombinedQueryPolicyResult = CombinedQueryPolicyResult(
        securedExpression = securedExpression,
        mandatoryExpression = MatchAll,
        constraints = QueryPolicyConstraints(fieldAccess, capabilityAccess, maxBudget)
    )

    private fun schema(fullText: Boolean = false): QuerySchema = QuerySchema(
        TARGET,
        QuerySystemFields.fields(QueryDocumentKind.SNAPSHOT) +
            QueryFieldSchema.string(STATUS, nullable = false).copy(
                sortable = true,
                capabilities = if (fullText) setOf(FULL_TEXT) else emptySet()
            )
    )

    private fun descriptor(
        documentKinds: Set<QueryDocumentKind> = setOf(QueryDocumentKind.SNAPSHOT),
        planVersions: Set<QueryPlanVersion> = setOf(QueryPlanVersion.V1),
        portableOperators: Set<PortableOperator> = PortableOperator.entries.toSet(),
        capabilities: Set<QueryCapabilityId> = emptySet(),
        maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED
    ): QueryBackendDescriptor = QueryBackendDescriptor(
        backendId = BACKEND_ID,
        documentKinds = documentKinds,
        planVersions = planVersions,
        portableOperators = portableOperators,
        capabilities = capabilities,
        maxBudget = maxBudget
    )

    private fun predicate(field: LogicalField, value: String): PredicateExpression = PredicateExpression(
        field,
        PortableOperator.EQ,
        listOf(QueryValue.StringValue(value))
    )

    companion object {
        private val TARGET = QueryTarget(
            object : NamedAggregate {
                override val contextName: String = "sales"
                override val aggregateName: String = "order"
            },
            QueryDocumentKind.SNAPSHOT
        )
        private val STATUS = LogicalField("state.status")
        private val LINES = LogicalField("state.lines")
        private val LINE_SKU = LogicalField("state.lines.sku")
        private val AGGREGATE_ID = LogicalField("aggregateId")
        private val FULL_TEXT = QueryCapabilityId("full-text")
        private const val BACKEND_ID = "recording"
        private val ROUTE = QueryBackendRouteIdentity("snapshot-primary")
        private val FROZEN = Instant.parse("2026-08-12T02:00:00Z")
    }
}
