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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

enum class QueryCapabilityPolicyDecision {
    GRANT,
    DENY,
    ABSTAIN,
}

class QueryCapabilityCase(
    val backendSupported: Boolean,
    val configured: Boolean,
    val policyDecision: QueryCapabilityPolicyDecision,
    val grantDenyDominance: Boolean = false,
) {
    val id: String = if (grantDenyDominance) {
        "backend-supported-configured-grant-deny"
    } else {
        "backend-$backendSupported-configured-$configured-policy-$policyDecision".lowercase()
    }
}

class QueryNativeCapabilityCase(
    val id: String,
    val expression: NativeExpression,
)

/**
 * Driver-neutral fixture for the capability admission contract. Implementations expose only framework query types
 * and aggregate raw data-command counts; driver requests and registries stay inside the concrete backend fixture.
 */
interface QueryCapabilityFixture {
    val id: String
    val capabilityId: QueryCapabilityId
    val target: QueryTarget
    val schema: QuerySchema
    val expression: QueryExpression
    val backendFactory: QueryBackendFactory
    val rawCommandCount: Long
    val nativePreflightCases: List<QueryNativeCapabilityCase>
        get() = emptyList()

    fun reset()
}

class QueryCapabilityContract(
    private val fixture: QueryCapabilityFixture,
) {
    fun dynamicTests(): Stream<DynamicTest> = (
        cases.map { case ->
            DynamicTest.dynamicTest("${fixture.id}-${case.id}") { verifyMatrixCase(case) }
        } + fixture.nativePreflightCases.map { case ->
            DynamicTest.dynamicTest("${fixture.id}-${case.id}") { verifyNativePreflight(case) }
        }
        ).stream()

    private fun verifyMatrixCase(case: QueryCapabilityCase) {
        fixture.reset()
        val probe = QueryCapabilityProbe()
        val gateway = gateway(
            expression = fixture.expression,
            backendSupported = case.backendSupported,
            configured = case.configured,
            policies = policies(case),
            probe = probe,
        )
        val request = CountQueryRequest(fixture.target, fixture.expression)
        val policyDenied = case.policyDecision != QueryCapabilityPolicyDecision.GRANT || case.grantDenyDominance
        val unsupported = !policyDenied && (!case.configured || !case.backendSupported)

        if (policyDenied) {
            verifyError(gateway.count(request), policyDenied())
        } else if (unsupported) {
            verifyError(gateway.count(request), unsupportedCapability())
        } else {
            StepVerifier.create(gateway.count(request)).expectNext(1L).verifyComplete()
        }

        val expectedResolver = if (policyDenied || !case.configured) 0L else 1L
        val expectedBind = if (expectedResolver == 1L) 1L else 0L
        val succeeds = !policyDenied && case.configured && case.backendSupported
        assertProbe(
            probe,
            resolver = expectedResolver,
            bind = expectedBind,
            readiness = if (succeeds) 1 else 0,
            execution = if (succeeds) 1 else 0,
            rawCommand = if (succeeds) 1 else 0,
        )
    }

    private fun verifyNativePreflight(case: QueryNativeCapabilityCase) {
        fixture.reset()
        val probe = QueryCapabilityProbe()
        val gateway = gateway(
            expression = case.expression,
            backendSupported = true,
            configured = true,
            policies = policies(
                QueryCapabilityCase(
                    backendSupported = true,
                    configured = true,
                    policyDecision = QueryCapabilityPolicyDecision.GRANT,
                ),
            ),
            probe = probe,
        )

        verifyError(gateway.count(CountQueryRequest(fixture.target, case.expression)), unsupportedCapability())
        assertProbe(probe, resolver = 1, bind = 1, readiness = 0, execution = 0, rawCommand = 0)
    }

    private fun gateway(
        expression: QueryExpression,
        backendSupported: Boolean,
        configured: Boolean,
        policies: List<QueryPolicy>,
        probe: QueryCapabilityProbe,
    ) = QueryGatewayFactory.create(
        QueryGatewayConfiguration(
            admission = QueryAdmission { context ->
                Mono.just(
                    QueryInvocationScope(
                        trustedAuthority = QueryAuthorityView(
                            subjectId = "query-capability-contract",
                            tenantId = null,
                            ownerId = null,
                            spaceIds = emptySet(),
                            permissions = setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS),
                        ),
                        requestedScope = context.request.requestedScope,
                        correlationId = context.correlationId,
                    ),
                )
            },
            schemaResolver = object : QuerySchemaResolver {
                override fun resolve(target: QueryTarget): Mono<QuerySchemaView> =
                    if (target == fixture.target) Mono.just(fixture.schema) else Mono.empty()
            },
            backendResolver = resolver(expression, backendSupported, probe),
            customPolicies = policies,
            resultPolicies = emptyList(),
            clock = Clock.fixed(FROZEN_INSTANT, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            structureLimits = QueryStructureLimits(
                maxDepth = 32,
                maxNodes = 512,
                maxMembershipItems = 512,
                maxNativeParameterBytes = 64 * 1024,
            ),
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            enabledCapabilities = if (configured) setOf(fixture.capabilityId) else emptySet(),
            meterRegistry = null,
        ),
    )

    private fun resolver(
        expression: QueryExpression,
        backendSupported: Boolean,
        probe: QueryCapabilityProbe,
    ): QueryBackendResolver = object : QueryBackendResolver {
        override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> =
            Mono.error(AssertionError("Capability contract used the target-only resolver path."))

        override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> = Mono.defer {
            probe.resolver.incrementAndGet()
            probe.bind.incrementAndGet()
            val backend = ObservedCapabilityBackend(
                delegate = fixture.backendFactory.bind(context),
                capabilityId = fixture.capabilityId,
                supported = backendSupported,
                probe = probe,
            )
            if (fixture.capabilityId !in backend.descriptor.capabilities ||
                expression is NativeExpression && expression.backendId != backend.descriptor.backendId
            ) {
                return@defer Mono.error(unsupportedCapability())
            }
            ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("capability-contract:${fixture.id}"))
        }
    }

    private fun policies(case: QueryCapabilityCase): List<QueryPolicy> {
        val primary = policy(case.policyDecision)
        return if (case.grantDenyDominance) {
            listOf(primary, policy(QueryCapabilityPolicyDecision.DENY))
        } else {
            listOf(primary)
        }
    }

    private fun policy(decision: QueryCapabilityPolicyDecision): QueryPolicy = QueryPolicy {
        Mono.just(
            QueryPolicyResult(
                constraints = QueryPolicyConstraints(
                    capabilityAccess = mapOf(
                        fixture.capabilityId to when (decision) {
                            QueryCapabilityPolicyDecision.GRANT -> CapabilityDecision.GRANT
                            QueryCapabilityPolicyDecision.DENY -> CapabilityDecision.DENY
                            QueryCapabilityPolicyDecision.ABSTAIN -> CapabilityDecision.ABSTAIN
                        },
                    ),
                ),
            ),
        )
    }

    private fun verifyError(publisher: Mono<Long>, expected: QueryException) {
        StepVerifier.create(publisher)
            .expectErrorSatisfies { error ->
                val actual = error as QueryException
                assertEquals(expected.code, actual.code)
                assertEquals(expected.stage, actual.stage)
                assertEquals(expected.reason, actual.reason)
            }
            .verify()
    }

    private fun assertProbe(
        probe: QueryCapabilityProbe,
        resolver: Long,
        bind: Long,
        readiness: Long,
        execution: Long,
        rawCommand: Long,
    ) {
        assertEquals(resolver, probe.resolver.get(), "resolver")
        assertEquals(bind, probe.bind.get(), "bind")
        assertEquals(readiness, probe.readiness.get(), "readiness")
        assertEquals(execution, probe.execution.get(), "execution")
        assertEquals(rawCommand, fixture.rawCommandCount, "raw command")
    }

    private class ObservedCapabilityBackend(
        private val delegate: QueryBackend,
        capabilityId: QueryCapabilityId,
        supported: Boolean,
        private val probe: QueryCapabilityProbe,
    ) : QueryBackend by delegate {
        override val descriptor: QueryBackendDescriptor = delegate.descriptor.withCapability(capabilityId, supported)

        override fun readiness(): Mono<QueryBackendReadiness> = Mono.defer {
            probe.readiness.incrementAndGet()
            delegate.readiness()
        }

        override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.defer {
            probe.execution.incrementAndGet()
            delegate.count(plan)
        }
    }

    companion object {
        @JvmField
        val cases: List<QueryCapabilityCase> = listOf(false, true).flatMap { backendSupported ->
            listOf(false, true).flatMap { configured ->
                QueryCapabilityPolicyDecision.entries.map { policy ->
                    QueryCapabilityCase(backendSupported, configured, policy)
                }
            }
        } + QueryCapabilityCase(
            backendSupported = true,
            configured = true,
            policyDecision = QueryCapabilityPolicyDecision.GRANT,
            grantDenyDominance = true,
        )

        private val FROZEN_INSTANT: Instant = Instant.parse("2026-08-12T00:00:00Z")
    }
}

private class QueryCapabilityProbe {
    val resolver = AtomicLong()
    val bind = AtomicLong()
    val readiness = AtomicLong()
    val execution = AtomicLong()
}

private fun QueryBackendDescriptor.withCapability(
    capabilityId: QueryCapabilityId,
    supported: Boolean,
): QueryBackendDescriptor = QueryBackendDescriptor(
    backendId = backendId,
    documentKinds = documentKinds,
    planVersions = planVersions,
    portableOperators = portableOperators,
    portableFeatures = portableFeatures,
    stringComparisonModes = stringComparisonModes,
    capabilities = if (supported) capabilities + capabilityId else capabilities - capabilityId,
    maxBudget = maxBudget,
)

private fun unsupportedCapability(): QueryException = QueryException(
    QueryErrorCode.UNSUPPORTED_CAPABILITY,
    QueryStage.PLANNING,
    QueryErrorReason.CAPABILITY_DENIED,
)

private fun policyDenied(): QueryException = QueryException(
    QueryErrorCode.POLICY_DENIED,
    QueryStage.POLICY,
    QueryErrorReason.CAPABILITY_DENIED,
)
