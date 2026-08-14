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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.GATEWAY_TARGET
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.expression.ExpressionNormalizer
import me.ahoo.wow.query.expression.LegacyConditionLowering
import me.ahoo.wow.query.gatewayConfiguration
import me.ahoo.wow.query.gatewayDescriptor
import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicReference

class LegacyQueryGatewayExecutionTest {

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
}
