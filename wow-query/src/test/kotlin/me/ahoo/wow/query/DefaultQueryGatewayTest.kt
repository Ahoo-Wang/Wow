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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DefaultQueryGatewayTest {
    @Test
    fun `all operations use the complete ordered pipeline`() {
        val operations = listOf(
            OperationCase("single", { gateway -> gateway.single(singleRequest()).then() }),
            OperationCase("list", { gateway -> gateway.list(listRequest()).then() }),
            OperationCase("page", { gateway -> gateway.page(pageRequest()).then() }),
            OperationCase("count", { gateway -> gateway.count(countRequest()).then() })
        )

        operations.forEach { operation ->
            val stages = CopyOnWriteArrayList<QueryGatewayStage>()
            val backend = RecordingQueryBackend(gatewayDescriptor())
                .respondSingle(Mono.just("single"))
                .respondList(Flux.just("list"))
                .respondPage(Mono.just(QueryPage(listOf("page"), 1, QueryConsistency.EXACT)))
                .respondCount(Mono.just(1))
            val gateway = DefaultQueryGatewayFactory.create(
                gatewayConfiguration(backend = backend),
                QueryGatewayStageObserver(stages::add)
            )

            StepVerifier.create(operation.invoke(gateway)).verifyComplete()

            stages.assert().isEqualTo(QueryGatewayStage.entries)
        }
    }

    @Test
    fun `gateway is cold and creates an independent invocation for each subscription`() {
        val admissionSubscriptions = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val base = gatewayConfiguration(backend)
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                admission = QueryPolicyTestAdmission(admissionSubscriptions)
            )
        )
        val result = gateway.count(countRequest())

        admissionSubscriptions.get().assert().isZero()
        backend.countSubscriptions.get().assert().isZero()
        StepVerifier.create(result).expectNext(1).verifyComplete()
        StepVerifier.create(result).expectNext(1).verifyComplete()

        admissionSubscriptions.get().assert().isEqualTo(2)
        backend.countSubscriptions.get().assert().isEqualTo(2)
        base.customPolicies.assert().isEmpty()
    }

    @Test
    fun `policy denial fails closed before backend resolution`() {
        val resolverCalls = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                customPolicies = listOf(
                    QueryPolicy { Mono.error(QueryPolicyDeniedException("DENIED")) }
                ),
                backendResolver = {
                    resolverCalls.incrementAndGet()
                    Mono.error(AssertionError("backend resolver must not run"))
                }
            )
        )

        StepVerifier.create(gateway.count(countRequest())).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
        }.verify()

        resolverCalls.get().assert().isZero()
        backend.readinessSubscriptions.get().assert().isZero()
        backend.countSubscriptions.get().assert().isZero()
    }

    @Test
    fun `admission extension failure is sanitized and never resolves a backend`() {
        val resolverCalls = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                admission = { Mono.error(IllegalStateException("sensitive authority")) },
                backendResolver = {
                    resolverCalls.incrementAndGet()
                    Mono.error(AssertionError("backend resolver must not run"))
                }
            )
        )

        StepVerifier.create(gateway.count(countRequest())).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                message.orEmpty().contains("sensitive").assert().isFalse()
            }
        }.verify()
        resolverCalls.get().assert().isZero()
    }

    private class OperationCase(
        val name: String,
        val invoke: (QueryGateway) -> Mono<Void>
    ) {
        override fun toString(): String = name
    }
}

private class QueryPolicyTestAdmission(
    private val subscriptions: AtomicInteger
) : me.ahoo.wow.query.invocation.QueryAdmission {
    override fun admit(
        context: me.ahoo.wow.query.invocation.QueryAdmissionContext
    ): Mono<me.ahoo.wow.query.invocation.QueryInvocationScope> = Mono.defer {
        subscriptions.incrementAndGet()
        Mono.just(
            me.ahoo.wow.query.invocation.QueryInvocationScope(
                me.ahoo.wow.query.invocation.QueryAuthorityView(
                    "subject",
                    "tenant",
                    "owner",
                    emptySet(),
                    emptySet()
                ),
                context.request.requestedScope,
                context.correlationId
            )
        )
    }
}

internal fun singleRequest(): SingleQueryRequest<String> = SingleQueryRequest(
    GATEWAY_TARGET,
    resultShape = GATEWAY_SHAPE
)

internal fun listRequest(): ListQueryRequest<String> = ListQueryRequest(GATEWAY_TARGET, resultShape = GATEWAY_SHAPE)

internal fun pageRequest(): PageQueryRequest<String> = PageQueryRequest(GATEWAY_TARGET, resultShape = GATEWAY_SHAPE)

internal fun countRequest(): CountQueryRequest = CountQueryRequest(GATEWAY_TARGET)
