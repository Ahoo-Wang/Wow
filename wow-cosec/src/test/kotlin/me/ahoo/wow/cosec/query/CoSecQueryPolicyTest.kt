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

package me.ahoo.wow.cosec.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.test.query.QueryPolicyContextBuilder
import me.ahoo.wow.test.query.QueryPolicyTestKit
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class CoSecQueryPolicyTest {
    private val policy = CoSecQueryPolicy()

    @Test
    fun `trusted tenant and all trusted spaces become mandatory portable scope`() {
        val authority = authority(tenantId = "tenant-a", spaces = linkedSetOf("space-b", "space-a"))
        val expected = and(
            predicate("tenantId", PortableOperator.EQ, "tenant-a"),
            predicate("spaceId", PortableOperator.IN, "space-a", "space-b")
        )

        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder().withAuthority(authority).build()
        ).expectMandatory(expected).block()
    }

    @Test
    fun `matching requested tenant and space narrow mandatory scope`() {
        val authority = authority(tenantId = "tenant-a", spaces = setOf("space-a", "space-b"))
        val expected = and(
            predicate("tenantId", PortableOperator.EQ, "tenant-a"),
            predicate("spaceId", PortableOperator.EQ, "space-b")
        )

        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder()
                .withAuthority(authority)
                .withRequestedScope(RequestedQueryScope(tenantId = "tenant-a", spaceId = "space-b"))
                .build()
        ).expectMandatory(expected).block()
    }

    @Test
    fun `missing and conflicting trusted scopes fail closed with stable reasons`() {
        val fixtures = listOf(
            QueryPolicyContextBuilder()
                .withAuthority(authority(tenantId = null, spaces = setOf("space")))
                .build() to "COSEC_TENANT_REQUIRED",
            QueryPolicyContextBuilder()
                .withAuthority(authority(tenantId = "tenant", spaces = emptySet()))
                .build() to "COSEC_SPACE_REQUIRED",
            QueryPolicyContextBuilder()
                .withAuthority(authority(tenantId = "tenant-a", spaces = setOf("space")))
                .withRequestedScope(RequestedQueryScope(tenantId = "tenant-b"))
                .build() to "COSEC_TENANT_MISMATCH",
            QueryPolicyContextBuilder()
                .withAuthority(authority(tenantId = "tenant", spaces = setOf("space-a")))
                .withRequestedScope(RequestedQueryScope(spaceId = "space-b"))
                .build() to "COSEC_SPACE_MISMATCH"
        )

        fixtures.forEach { (context, reason) ->
            QueryPolicyTestKit(policy, context).expectDenied(reason).block()
        }
    }

    @Test
    fun `caller requested tenant and space cannot substitute for missing trusted authority`() {
        QueryPolicyTestKit(
            policy,
            QueryPolicyContextBuilder()
                .withAuthority(authority(tenantId = null, spaces = emptySet()))
                .withRequestedScope(RequestedQueryScope(tenantId = "forged-tenant", spaceId = "forged-space"))
                .build()
        ).expectDenied("COSEC_TENANT_REQUIRED").block()
    }

    @Test
    fun `forged requested scope is denied before backend resolution`() {
        val target = QueryPolicyContextBuilder.DEFAULT_TARGET
        val backendResolutions = AtomicInteger()
        val gateway = QueryGatewayFactory.create(
            QueryGatewayConfiguration(
                admission = QueryAdmission { admission ->
                    Mono.just(
                        QueryInvocationScope(
                            authority(tenantId = null, spaces = emptySet()),
                            admission.request.requestedScope,
                            admission.correlationId
                        )
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<QuerySchemaView> =
                        Mono.just(QuerySchema(target, QuerySystemFields.fields(target.documentKind)))
                },
                backendResolver = QueryBackendResolver {
                    backendResolutions.incrementAndGet()
                    Mono.error(AssertionError("backend must not resolve"))
                },
                customPolicies = listOf(policy),
                resultPolicies = emptyList(),
                clock = Clock.systemUTC(),
                zoneId = ZoneOffset.UTC,
                structureLimits = QueryStructureLimits(16, 128, 128, 4096),
                systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
                enabledCapabilities = emptySet(),
                meterRegistry = null
            )
        )

        gateway.count(
            CountQueryRequest(
                target = target,
                requestedScope = RequestedQueryScope(tenantId = "forged-tenant", spaceId = "forged-space")
            )
        ).test()
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
            }
            .verify()
        backendResolutions.get().assert().isZero()
    }

    @Test
    fun `legacy QueryService scope mismatch and missing authority stop before backend resolution`() {
        val fixtures = listOf(
            LegacySecurityCase(
                Condition.tenantId("tenant-b"),
                authority(tenantId = "tenant-a", spaces = setOf("space-a")),
            ),
            LegacySecurityCase(
                Condition.spaceId("space-b"),
                authority(tenantId = "tenant-a", spaces = setOf("space-a")),
            ),
            LegacySecurityCase(
                Condition.tenantId("forged-tenant"),
                authority(tenantId = null, spaces = setOf("space-a")),
            ),
            LegacySecurityCase(
                Condition.spaceId("forged-space"),
                authority(tenantId = "tenant-a", spaces = emptySet()),
            ),
        )

        fixtures.forEach { fixture ->
            val security = legacySecurity(fixture.authority)
            val service = GatewaySnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, security.gateway)

            service.count(fixture.condition).test()
                .expectErrorSatisfies { error ->
                    (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
                }
                .verify()

            security.backendResolutions.get().assert().isZero()
            security.backend.readinessSubscriptions.get().assert().isZero()
            security.backend.countSubscriptions.get().assert().isZero()
        }
    }

    @Test
    fun `matching legacy QueryService scopes retain caller provenance and mandatory trusted scope`() {
        val security = legacySecurity(authority(tenantId = "tenant-a", spaces = setOf("space-a", "space-b")))
        val service = GatewaySnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, security.gateway)

        service.count(
            Condition.and(Condition.tenantId("tenant-a"), Condition.spaceId("space-b")),
        ).test().expectNext(1L).verifyComplete()

        security.backendResolutions.get().assert().isOne()
        security.backend.readinessSubscriptions.get().assert().isOne()
        security.backend.countSubscriptions.get().assert().isOne()
        security.backend.countPlans.single().apply {
            expressionProvenance[QueryProvenance.CALLER_REQUEST].assert().isEqualTo(
                and(
                    predicate("tenantId", PortableOperator.EQ, "tenant-a"),
                    predicate("spaceId", PortableOperator.EQ, "space-b"),
                ),
            )
            expressionProvenance[QueryProvenance.MANDATORY_POLICY].assert().isEqualTo(
                and(
                    PredicateExpression(LogicalField("deleted"), PortableOperator.FALSE, emptyList()),
                    predicate("tenantId", PortableOperator.EQ, "tenant-a"),
                    predicate("spaceId", PortableOperator.EQ, "space-b"),
                ),
            )
        }
    }

    private fun legacySecurity(authority: QueryAuthorityView): LegacySecurityFixture {
        val backend = LegacySecurityBackend()
        val backendResolutions = AtomicInteger()
        val gateway = QueryGatewayFactory.create(
            QueryGatewayConfiguration(
                admission = QueryAdmission { admission ->
                    Mono.just(
                        QueryInvocationScope(authority, admission.request.requestedScope, admission.correlationId)
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<QuerySchemaView> =
                        Mono.just(QuerySchema(target, QuerySystemFields.fields(target.documentKind)))
                },
                backendResolver = QueryBackendResolver {
                    backendResolutions.incrementAndGet()
                    ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("legacy-security"))
                },
                customPolicies = listOf(policy),
                resultPolicies = emptyList(),
                clock = Clock.systemUTC(),
                zoneId = ZoneOffset.UTC,
                structureLimits = QueryStructureLimits(16, 128, 128, 4096),
                systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
                enabledCapabilities = emptySet(),
                meterRegistry = null,
            ),
        )
        return LegacySecurityFixture(gateway, backend, backendResolutions)
    }

    private fun authority(tenantId: String?, spaces: Set<String>): QueryAuthorityView = QueryAuthorityView(
        subjectId = "subject",
        tenantId = tenantId,
        ownerId = null,
        spaceIds = spaces,
        permissions = emptySet()
    )

    private fun predicate(field: String, operator: PortableOperator, vararg values: String): PredicateExpression =
        PredicateExpression(
            LogicalField(field),
            operator,
            values.map { value -> QueryValue.StringValue(value) }
        )

    private fun and(vararg expressions: PredicateExpression): PortableLogicalExpression =
        PortableLogicalExpression(LogicalOperator.AND, expressions.toList())

    private data class LegacySecurityCase(
        val condition: Condition,
        val authority: QueryAuthorityView,
    )

    private data class LegacySecurityFixture(
        val gateway: me.ahoo.wow.query.QueryGateway,
        val backend: LegacySecurityBackend,
        val backendResolutions: AtomicInteger,
    )

    private class LegacySecurityBackend : QueryBackend {
        override val descriptor: QueryBackendDescriptor = QueryBackendDescriptor(
            backendId = "legacy-security",
            documentKinds = me.ahoo.wow.api.query.gateway.QueryDocumentKind.entries.toSet(),
            planVersions = setOf(QueryPlanVersion.V1),
            portableOperators = PortableOperator.entries.toSet(),
            portableFeatures = QueryPortableFeature.entries.toSet(),
            stringComparisonModes = me.ahoo.wow.api.query.expression.StringComparisonMode.entries.toSet(),
            capabilities = emptySet(),
            maxBudget = QueryBudgetLimit.UNBOUNDED,
        )
        val readinessSubscriptions = AtomicInteger()
        val countSubscriptions = AtomicInteger()
        val countPlans = mutableListOf<CountQueryPlanV1>()

        override fun readiness(): Mono<QueryBackendReadiness> = Mono.fromSupplier {
            readinessSubscriptions.incrementAndGet()
            QueryBackendReadiness.Ready
        }

        override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.empty()

        override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.empty()

        override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.empty()

        override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.fromSupplier {
            countSubscriptions.incrementAndGet()
            countPlans += plan
            1L
        }
    }
}
