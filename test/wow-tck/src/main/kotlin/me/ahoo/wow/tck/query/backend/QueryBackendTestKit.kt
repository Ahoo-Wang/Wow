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

import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackend
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
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.query.validation.QueryStructureLimits
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class QueryBackendTestKit(
    backendFactory: QueryBackendFactory,
    val documentKind: QueryDocumentKind,
    expectedCapabilities: Set<QueryCapabilityId> = emptySet(),
    expectedReadiness: QueryBackendReadiness = QueryBackendReadiness.Ready,
    private val fieldAccess: QueryFieldAccess = QueryFieldAccess.UNRESTRICTED
) {
    private val observation = QueryBackendObservation()
    private val observedFactory = QueryBackendFactory { context ->
        observation.contextResolutions.incrementAndGet()
        ObservedQueryBackend(
            delegate = backendFactory.bind(context),
            observation = observation,
            expectedCapabilities = expectedCapabilities,
            expectedReadiness = expectedReadiness
        )
    }
    private val resolver: QueryBackendResolver = object : QueryBackendResolver {
        override fun resolve(target: QueryTarget): Mono<ResolvedQueryBackend> {
            observation.targetOnlyResolutions.incrementAndGet()
            return Mono.error(AssertionError("QueryGateway used the target-only backend resolver path."))
        }

        override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> = Mono.defer {
            ResolvedQueryBackend.resolve(observedFactory.bind(context), ROUTE_IDENTITY)
        }
    }

    val gateway: QueryGateway = QueryGatewayFactory.create(configuration())
    val target: QueryTarget = PortableQueryDataset.target(documentKind)

    val contextResolutionCount: Long
        get() = observation.contextResolutions.get()

    val targetOnlyResolutionCount: Long
        get() = observation.targetOnlyResolutions.get()

    val executionSubscriptionCount: Long
        get() = observation.executionSubscriptions.get()

    val listRequestCount: Long
        get() = observation.listRequests.get()

    private fun configuration(): QueryGatewayConfiguration {
        val capabilityDecisions = ENABLED_CAPABILITIES.associateWith { CapabilityDecision.GRANT }
        val capabilityPolicy = QueryPolicy {
            Mono.just(
                QueryPolicyResult(
                    constraints = QueryPolicyConstraints(
                        fieldAccess = fieldAccess,
                        capabilityAccess = capabilityDecisions
                    )
                )
            )
        }
        return QueryGatewayConfiguration(
            admission = QueryAdmission { context ->
                Mono.just(
                    QueryInvocationScope(
                        trustedAuthority = QueryAuthorityView(
                            subjectId = "portable-query-subject",
                            tenantId = null,
                            ownerId = null,
                            spaceIds = emptySet(),
                            permissions = setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
                        ),
                        requestedScope = context.request.requestedScope,
                        correlationId = context.correlationId
                    )
                )
            },
            schemaResolver = object : QuerySchemaResolver {
                override fun resolve(target: QueryTarget): Mono<QuerySchemaView> =
                    if (target == this@QueryBackendTestKit.target) {
                        Mono.just(PortableQueryDataset.schema(documentKind))
                    } else {
                        Mono.empty()
                    }
            },
            backendResolver = resolver,
            customPolicies = listOf(capabilityPolicy),
            resultPolicies = emptyList(),
            clock = Clock.fixed(FROZEN_INSTANT, ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            structureLimits = QueryStructureLimits(
                maxDepth = 32,
                maxNodes = 512,
                maxMembershipItems = 512,
                maxNativeParameterBytes = 64 * 1024
            ),
            systemBudgetLimit = QueryBudgetLimit.UNBOUNDED,
            enabledCapabilities = ENABLED_CAPABILITIES,
            meterRegistry = null
        )
    }

    private class ObservedQueryBackend(
        private val delegate: QueryBackend,
        private val observation: QueryBackendObservation,
        expectedCapabilities: Set<QueryCapabilityId>,
        private val expectedReadiness: QueryBackendReadiness
    ) : QueryBackend {
        override val descriptor = delegate.descriptor.also { descriptor ->
            require(descriptor.capabilities.containsAll(expectedCapabilities)) {
                "Backend descriptor does not contain the declared TCK capability fixture."
            }
        }

        override fun readiness(): Mono<QueryBackendReadiness> = delegate.readiness().doOnNext { actual ->
            require(actual == expectedReadiness) { "Backend readiness differs from the declared TCK fixture." }
        }

        override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> =
            observe(delegate.single(plan))

        override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = observe(delegate.list(plan))

        override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> =
            observe(delegate.page(plan))

        override fun count(plan: CountQueryPlanV1): Mono<Long> = observe(delegate.count(plan))

        private fun <T : Any> observe(publisher: Mono<T>): Mono<T> = Mono.defer {
            observation.executionSubscriptions.incrementAndGet()
            publisher
        }

        private fun <T : Any> observe(publisher: Flux<T>): Flux<T> = Flux.defer {
            observation.executionSubscriptions.incrementAndGet()
            publisher
        }.doOnRequest(observation::recordRequest)
    }

    private class QueryBackendObservation {
        val contextResolutions: AtomicLong = AtomicLong()
        val targetOnlyResolutions: AtomicLong = AtomicLong()
        val executionSubscriptions: AtomicLong = AtomicLong()
        val listRequests: AtomicLong = AtomicLong()

        fun recordRequest(requested: Long) {
            listRequests.updateAndGet { current ->
                if (Long.MAX_VALUE - current < requested) Long.MAX_VALUE else current + requested
            }
        }
    }

    private companion object {
        val FROZEN_INSTANT: Instant = Instant.parse("2026-08-12T00:00:00Z")
        val ROUTE_IDENTITY: QueryBackendRouteIdentity = QueryBackendRouteIdentity("portable-query-tck")
        val ENABLED_CAPABILITIES: Set<QueryCapabilityId> = setOf(
            PortableQueryDataset.FULL_TEXT_CAPABILITY,
            PortableQueryDataset.UNSUPPORTED_CAPABILITY
        )
    }
}
