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

@file:Suppress("DEPRECATION_ERROR")

package me.ahoo.wow.query.compat

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.expression.LegacyConditionLowering
import me.ahoo.wow.query.gatewayConfiguration
import me.ahoo.wow.query.gatewayDescriptor
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryStructureLimits
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LegacyQueryGatewayExecutionTest {

    @Test
    fun `legacy entry is identified in gateway metrics even without a rewrite`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend, meterRegistry = registry))
        val original = Condition.eq("state.status", "OPEN")

        LegacyQueryGatewayExecution.count(gateway, GATEWAY_TARGET, original, original)
            .test()
            .expectNext(1)
            .verifyComplete()

        registry.get("wow.query.gateway")
            .tag("legacyFacade", "true")
            .counter().count().assert().isEqualTo(1.0)
    }

    @Test
    fun `append-only rewrite keeps caller and legacy provenance separate`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(
            Flux.just(ImmutableDynamicDocument.copyOf(mapOf("state.status" to "OPEN")))
        )
        val requestedTenant = AtomicReference<String?>()
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                admission = QueryAdmission { context ->
                    requestedTenant.set(context.request.requestedScope.tenantId)
                    Mono.just(
                        QueryInvocationScope(
                            QueryAuthorityView("subject", "tenant", null, emptySet(), emptySet()),
                            context.request.requestedScope,
                            context.correlationId
                        )
                    )
                }
            )
        )
        val original = ListQuery(Condition.eq("state.status", "OPEN"))
        val addition = Condition.tenantId("tenant")
        val rewritten = original.withCondition(Condition.and(original.condition, addition))

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
            .test()
            .expectNextCount(1)
            .verifyComplete()

        val plan = backend.listPlans.single()
        plan.expressionProvenance.keys.assert().containsExactly(
            QueryProvenance.CALLER_REQUEST,
            QueryProvenance.LEGACY_ENRICHMENT,
            QueryProvenance.MANDATORY_POLICY
        )
        plan.expressionProvenance.getValue(QueryProvenance.CALLER_REQUEST).assert()
            .isEqualTo(LegacyConditionLowering.lowerForGateway(original.condition, GATEWAY_TARGET).first)
        plan.expressionProvenance.getValue(QueryProvenance.LEGACY_ENRICHMENT).assert()
            .isEqualTo(LegacyConditionLowering.lowerForGateway(addition, GATEWAY_TARGET).first)
        requestedTenant.get().assert().isEqualTo("tenant")
    }

    @Test
    fun `nested original scope and multiple additions merge without losing provenance`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.empty<ImmutableDynamicDocument>())
        val requestedScope = AtomicReference<RequestedQueryScope>()
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                admission = QueryAdmission { context ->
                    requestedScope.set(context.request.requestedScope)
                    Mono.just(
                        QueryInvocationScope(
                            QueryAuthorityView(
                                "subject",
                                "tenant",
                                "owner",
                                setOf("space"),
                                setOf(QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS)
                            ),
                            context.request.requestedScope,
                            context.correlationId
                        )
                    )
                }
            )
        )
        val originalCondition = Condition.and(
            Condition.deleted(DeletionState.DELETED),
            Condition.eq("state.status", "OPEN")
        )
        val additions = listOf(
            Condition.tenantId("tenant"),
            Condition.ownerId("owner"),
            Condition.spaceId("space")
        )
        val original = ListQuery(originalCondition)
        val rewritten = original.withCondition(Condition.and(listOf(originalCondition) + additions))

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
            .test()
            .verifyComplete()

        requestedScope.get().assert().isEqualTo(
            RequestedQueryScope(
                tenantId = "tenant",
                ownerId = "owner",
                spaceId = "space",
                deletion = DeletionScope.DELETED
            )
        )
        backend.listPlans.single().expressionProvenance.let { provenance ->
            provenance.getValue(QueryProvenance.CALLER_REQUEST).assert().isEqualTo(
                LegacyConditionLowering.lowerForGateway(originalCondition, GATEWAY_TARGET).first
            )
            provenance.getValue(QueryProvenance.LEGACY_ENRICHMENT).assert().isEqualTo(
                LegacyConditionLowering.lowerForGateway(Condition.and(additions), GATEWAY_TARGET).first
            )
        }
    }

    @Test
    fun `conflicting original and addition scopes fail before backend resolution`() {
        val cases = listOf(
            ScopeConflict(
                Condition.tenantId("tenant-old"),
                Condition.tenantId("tenant")
            ),
            ScopeConflict(
                Condition.ownerId("owner-old"),
                Condition.ownerId("owner")
            ),
            ScopeConflict(
                Condition.spaceId("space-old"),
                Condition.spaceId("space")
            ),
            ScopeConflict(
                Condition.deleted(DeletionState.ACTIVE),
                Condition.deleted(DeletionState.DELETED)
            )
        )

        cases.forEach { case ->
            val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.empty<ImmutableDynamicDocument>())
            val gateway = QueryGatewayFactory.create(
                gatewayConfiguration(
                    backend,
                    admission = QueryAdmission { context ->
                        Mono.just(
                            QueryInvocationScope(
                                QueryAuthorityView("subject", "tenant", "owner", setOf("space"), emptySet()),
                                context.request.requestedScope,
                                context.correlationId
                            )
                        )
                    }
                )
            )
            val originalCondition = Condition.and(case.original, Condition.eq("state.status", "OPEN"))
            val original = ListQuery(originalCondition)
            val rewritten = original.withCondition(Condition.and(originalCondition, case.addition))

            LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
                .test()
                .consumeErrorWith(::assertInvalidRewrite)
                .verify()

            backend.readinessSubscriptions.get().assert().isZero()
            backend.listSubscriptions.get().assert().isZero()
        }
    }

    @Test
    fun `legacy additions obey configured structure limits before gateway work`() {
        val cases = listOf(
            StructureCase(
                QueryStructureLimits(
                    maxDepth = 2,
                    maxNodes = 128,
                    maxMembershipItems = 128,
                    maxNativeParameterBytes = 4096
                ),
                Condition.and(Condition.and(Condition.eq("state.status", "OPEN")))
            ),
            StructureCase(
                QueryStructureLimits(
                    maxDepth = 16,
                    maxNodes = 4,
                    maxMembershipItems = 128,
                    maxNativeParameterBytes = 4096
                ),
                Condition.and(List(4) { Condition.eq("state.status", "OPEN") })
            ),
            StructureCase(
                QueryStructureLimits(
                    maxDepth = 16,
                    maxNodes = 128,
                    maxMembershipItems = 2,
                    maxNativeParameterBytes = 4096
                ),
                Condition.isIn("state.status", listOf("OPEN", "CLOSED", "CANCELLED"))
            ),
            StructureCase(
                QueryStructureLimits(
                    maxDepth = 16,
                    maxNodes = 128,
                    maxMembershipItems = 128,
                    maxNativeParameterBytes = 4
                ),
                Condition.raw(
                    NativeExpression(
                        QueryCapabilityId("native"),
                        "recording",
                        "template",
                        mapOf("k" to QueryValue.StringValue("v")),
                        setOf(LogicalField("state.status"))
                    )
                )
            )
        )

        cases.forEach(::assertRejectedStructureCase)
    }

    @Test
    fun `custom gateway receives an effective append-only expression`() {
        val captured = AtomicReference<ListQueryRequest<*>>()
        val gateway = object : QueryGateway {
            override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.empty()

            override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> {
                captured.set(request)
                return Flux.empty()
            }

            override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.empty()

            override fun count(request: CountQueryRequest): Mono<Long> = Mono.just(0)
        }
        val original = ListQuery(Condition.eq("state.status", "OPEN"))
        val addition = Condition.ownerId("owner")
        val rewritten = original.withCondition(Condition.and(original.condition, addition))

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
            .test()
            .verifyComplete()

        captured.get().expression.assert().isEqualTo(
            ExpressionNormalizer.logical(
                LogicalOperator.AND,
                listOf(
                    LegacyConditionLowering.lowerForGateway(original.condition, GATEWAY_TARGET).first,
                    LegacyConditionLowering.lowerForGateway(addition, GATEWAY_TARGET).first
                )
            )
        )
    }

    @Test
    fun `replacement rewrite fails closed before gateway execution`() {
        val gateway = RecordingGateway()
        val original = ListQuery(Condition.eq("state.status", "OPEN"))
        val replacement = original.withCondition(Condition.eq("state.status", "CLOSED"))

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, replacement)
            .test()
            .consumeErrorWith(::assertInvalidRewrite)
            .verify()

        gateway.listCalls.assert().isEqualTo(0)
    }

    @Test
    fun `rewrite cannot change non-condition query fields`() {
        val gateway = RecordingGateway()
        val original = ListQuery(Condition.eq("state.status", "OPEN"))
        val rewritten = original.copy(
            condition = Condition.and(original.condition, Condition.ownerId("owner")),
            sort = listOf(Sort("state.status", Sort.Direction.ASC))
        )

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
            .test()
            .consumeErrorWith(::assertInvalidRewrite)
            .verify()

        gateway.listCalls.assert().isEqualTo(0)
    }

    private fun assertInvalidRewrite(error: Throwable) {
        error.assert().isInstanceOf(QueryException::class.java)
        (error as QueryException).let {
            it.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
            it.stage.assert().isEqualTo(QueryStage.ADMISSION)
            it.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
        }
    }

    private class RecordingGateway : QueryGateway {
        var listCalls: Int = 0

        override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.empty()

        override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> {
            listCalls++
            return Flux.empty()
        }

        override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.empty()

        override fun count(request: CountQueryRequest): Mono<Long> = Mono.just(0)
    }

    private data class ScopeConflict(
        val original: Condition,
        val addition: Condition
    )

    private fun assertRejectedStructureCase(case: StructureCase) {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.empty<ImmutableDynamicDocument>())
        val admissionCalls = AtomicInteger()
        val schemaResolverCalls = AtomicInteger()
        val backendResolverCalls = AtomicInteger()
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                admission = QueryAdmission { context ->
                    admissionCalls.incrementAndGet()
                    Mono.just(
                        QueryInvocationScope(
                            QueryAuthorityView("subject", "tenant", "owner", emptySet(), emptySet()),
                            context.request.requestedScope,
                            context.correlationId
                        )
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<QuerySchemaView> =
                        Mono.just<QuerySchemaView>(
                            me.ahoo.wow.query.gatewaySchema().also { require(it.target == target) }
                        ).doOnSubscribe { schemaResolverCalls.incrementAndGet() }
                },
                backendResolver = QueryBackendResolver {
                    backendResolverCalls.incrementAndGet()
                    ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("gateway-route"))
                },
                structureLimits = case.limits
            )
        )
        val original = ListQuery(Condition.eq("state.status", "OPEN"))
        val rewritten = original.withCondition(Condition.and(original.condition, case.addition))

        LegacyQueryGatewayExecution.list(gateway, GATEWAY_TARGET, original, rewritten)
            .test()
            .consumeErrorWith { error ->
                (error as QueryException).let { queryError ->
                    queryError.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
                    queryError.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
                }
            }.verify()

        admissionCalls.get().assert().isZero()
        schemaResolverCalls.get().assert().isZero()
        backendResolverCalls.get().assert().isZero()
        backend.readinessSubscriptions.get().assert().isZero()
        backend.listSubscriptions.get().assert().isZero()
    }
}

private data class StructureCase(
    val limits: QueryStructureLimits,
    val addition: Condition
)
