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
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchemaResolver
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
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
    fun `backend resolution receives an isolated context with the final secured expression`() {
        val contexts = CopyOnWriteArrayList<QueryBackendResolutionContext>()
        val schemaBackings = CopyOnWriteArrayList<MutableMap<LogicalField, QueryFieldSchema>>()
        val mandatoryExpression = PredicateExpression(
            GATEWAY_STATUS,
            PortableOperator.EQ,
            listOf(QueryValue.StringValue("policy-secret"))
        )
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val resolver = object : QueryBackendResolver {
            override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<ResolvedQueryBackend> =
                Mono.error(AssertionError("Gateway used the target-only compatibility path."))

            override fun resolve(context: QueryBackendResolutionContext): Mono<ResolvedQueryBackend> {
                contexts += context
                schemaBackings.last().clear()
                context.schema.fields.assert().isNotEmpty()
                return ResolvedQueryBackend.resolve(backend, QueryBackendRouteIdentity("gateway-route"))
            }
        }
        val schemaResolver = object : QuerySchemaResolver {
            override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<QuerySchemaView> =
                Mono.fromSupplier {
                    val backing = LinkedHashMap(gatewaySchema().fields)
                    schemaBackings += backing
                    object : QuerySchemaView {
                        override val target = target
                        override val fields = backing
                    }
                }
        }
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                customPolicies = listOf(QueryPolicy { Mono.just(QueryPolicyResult(mandatoryExpression)) }),
                schemaResolver = schemaResolver,
                backendResolver = resolver
            )
        )
        val result = gateway.count(countRequest())

        StepVerifier.create(result).expectNext(1).verifyComplete()
        StepVerifier.create(result).expectNext(1).verifyComplete()

        contexts.assert().hasSize(2)
        contexts[0].assert().isNotSameAs(contexts[1])
        contexts.forEachIndexed { index, context ->
            context.target.assert().isEqualTo(GATEWAY_TARGET)
            context.schema.assert().isEqualTo(gatewaySchema())
            context.schema.assert().isNotSameAs(contexts[1 - index].schema)
            context.securedExpression.assert().isSameAs(backend.countPlans[index].securedExpression)
        }
        schemaBackings.forEach { it.assert().isEmpty() }

        val first = contexts.first()
        val copied = first.copy()
        val (target, schema, securedExpression) = copied
        copied.assert().isEqualTo(first)
        copied.hashCode().assert().isEqualTo(first.hashCode())
        target.assert().isEqualTo(GATEWAY_TARGET)
        schema.assert().isSameAs(first.schema)
        securedExpression.assert().isSameAs(first.securedExpression)
        copied.toString().assert().isEqualTo(
            "QueryBackendResolutionContext(target=<redacted>, " +
                "schemaFieldCount=${gatewaySchema().fields.size}, securedExpression=<redacted>)"
        )
        copied.toString().contains("policy-secret").assert().isFalse()
        copied.toString().contains(GATEWAY_TARGET.namedAggregate.aggregateName).assert().isFalse()
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
    fun `policy failure fails closed before backend resolution`() {
        val resolverCalls = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                customPolicies = listOf(
                    QueryPolicy { Mono.error(IllegalStateException("sensitive policy failure")) }
                ),
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

    @Test
    fun `finite admission guard includes protocol validation before schema and backend resolution`() {
        val schemaCalls = AtomicInteger()
        val backendCalls = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend = backend,
                admission = { context ->
                    Mono.just(
                        QueryInvocationScope(
                            QueryAuthorityView("subject", "tenant", "owner", emptySet(), emptySet()),
                            context.request.requestedScope,
                            "forged-correlation"
                        )
                    )
                },
                schemaResolver = object : QuerySchemaResolver {
                    override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget): Mono<QuerySchemaView> {
                        schemaCalls.incrementAndGet()
                        return Mono.error(AssertionError("schema resolver must not run"))
                    }
                },
                backendResolver = {
                    backendCalls.incrementAndGet()
                    Mono.error(AssertionError("backend resolver must not run"))
                },
                systemBudgetLimit = QueryBudgetLimit(timeout = Duration.ofSeconds(1))
            )
        )

        StepVerifier.create(gateway.count(countRequest())).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                stage.assert().isEqualTo(QueryStage.ADMISSION)
                reason.assert().isEqualTo(QueryErrorReason.POLICY_EVALUATION_FAILED)
                message.orEmpty().contains("forged-correlation").assert().isFalse()
            }
        }.verify()
        schemaCalls.get().assert().isZero()
        backendCalls.get().assert().isZero()
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
