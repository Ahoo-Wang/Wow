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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.result.ResultPolicy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewayLifecycleTest {
    @Test
    fun `list maps a deadline after its first item to incomplete result and cancels backend`() {
        val scheduler = VirtualTimeScheduler.create()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(
            Flux.concat(Flux.just("one"), Flux.never<String>())
        )
        val request = ListQueryRequest(
            target = GATEWAY_TARGET,
            resultShape = GATEWAY_SHAPE,
            budget = QueryBudgetHint(timeout = Duration.ofSeconds(1))
        )

        StepVerifier.withVirtualTime(
            { QueryGatewayFactory.create(gatewayConfiguration(backend)).list(request) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .expectNext("one")
            .thenAwait(Duration.ofSeconds(1))
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                    stage.assert().isEqualTo(QueryStage.EXECUTION)
                    reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
                    causeCode.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
                    cause.assert().isNull()
                    suppressed.toList().assert().isEmpty()
                }
            }.verify(Duration.ofSeconds(1))

        backend.listSubscriptions.get().assert().isOne()
        backend.cancellations.get().assert().isOne()
    }

    @Test
    fun `list preserves a deadline before its first item`() {
        val scheduler = VirtualTimeScheduler.create()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.never<String>())
        val request = ListQueryRequest(
            target = GATEWAY_TARGET,
            resultShape = GATEWAY_SHAPE,
            budget = QueryBudgetHint(timeout = Duration.ofSeconds(1))
        )

        StepVerifier.withVirtualTime(
            { QueryGatewayFactory.create(gatewayConfiguration(backend)).list(request) },
            { scheduler },
            Long.MAX_VALUE
        ).expectSubscription()
            .thenAwait(Duration.ofSeconds(1))
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
            }.verify(Duration.ofSeconds(1))

        backend.listSubscriptions.get().assert().isOne()
        backend.cancellations.get().assert().isOne()
    }

    @Test
    fun `single waits for backend and result policy completion before emitting`() {
        val backendPublisher = TestPublisher.create<String>()
        val resultPublisher = TestPublisher.create<Any>()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.fromDirect(backendPublisher))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                resultPolicies = listOf(ResultPolicy { _, _ -> Mono.fromDirect(resultPublisher) })
            )
        )

        StepVerifier.create(gateway.single(singleRequest()))
            .expectSubscription()
            .then { backendPublisher.next("backend") }
            .expectNoEvent(java.time.Duration.ofMillis(10))
            .then { backendPublisher.complete() }
            .then { resultPublisher.next("result") }
            .expectNoEvent(java.time.Duration.ofMillis(10))
            .then { resultPublisher.complete() }
            .expectNext("result")
            .verifyComplete()
    }

    @Test
    fun `single does not emit until result policy completes`() {
        val resultPolicySubscriptions = AtomicInteger()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.just("value"))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                resultPolicies = listOf(
                    ResultPolicy { _, _ ->
                        Mono.defer {
                            resultPolicySubscriptions.incrementAndGet()
                            Mono.never()
                        }
                    }
                )
            )
        )

        StepVerifier.create(gateway.single(singleRequest())).expectSubscription().then {
            backend.terminals.get().assert().isOne()
            resultPolicySubscriptions.get().assert().isOne()
        }.expectNoEvent(java.time.Duration.ofMillis(10)).thenCancel().verify()
    }

    @Test
    fun `list honors downstream demand and emits incrementally`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(
            Flux.just("one", "two")
        )
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend))

        StepVerifier.create(gateway.list(listRequest()), 0)
            .expectSubscription()
            .expectNoEvent(java.time.Duration.ofMillis(10))
            .thenRequest(1)
            .expectNext("one")
            .expectNoEvent(java.time.Duration.ofMillis(10))
            .thenRequest(1)
            .expectNext("two")
            .verifyComplete()
    }

    @Test
    fun `list preserves a pre emission error and maps a post emission error to incomplete result`() {
        val concrete = QueryException(
            QueryErrorCode.BACKEND_FAILURE,
            QueryStage.EXECUTION,
            QueryErrorReason.BACKEND_EXECUTION_FAILED
        )
        val beforeBackend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.error<String>(concrete))
        val beforeGateway = QueryGatewayFactory.create(gatewayConfiguration(beforeBackend))

        StepVerifier.create(beforeGateway.list(listRequest())).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.BACKEND_FAILURE)
        }.verify()

        val cancelled = AtomicInteger()
        val afterBackend = RecordingQueryBackend(gatewayDescriptor()).respondList(
            Flux.just("one", "bad", "unreachable").doOnCancel(cancelled::incrementAndGet)
        )
        val afterGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                afterBackend,
                resultPolicies = listOf(
                    ResultPolicy { _, value ->
                        if (value == "bad") Mono.error(IllegalStateException("sensitive")) else Mono.just(value)
                    }
                )
            )
        )

        StepVerifier.create(afterGateway.list(listRequest())).expectNext("one").expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                stage.assert().isEqualTo(QueryStage.EXECUTION)
                reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
                causeCode.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
                cause.assert().isNull()
                suppressed.toList().assert().isEmpty()
                message.orEmpty().contains("sensitive").assert().isFalse()
            }
        }.verify()
        cancelled.get().assert().isOne()
    }

    @Test
    fun `list retains backend failure as safe cause code after its first item`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(
            Flux.concat(Flux.just("one"), Flux.error(IllegalStateException("backend-sensitive")))
        )
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend))

        StepVerifier.create(gateway.list(listRequest())).expectNext("one").expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                causeCode.assert().isEqualTo(QueryErrorCode.BACKEND_FAILURE)
                cause.assert().isNull()
                suppressed.toList().assert().isEmpty()
                message.orEmpty().contains("backend-sensitive").assert().isFalse()
            }
        }.verify()
    }

    @Test
    fun `list retains result validation failure as safe cause code after its first item`() {
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondList(Flux.just("one", "bad"))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                resultPolicies = listOf(
                    ResultPolicy { _, value -> if (value == "bad") Mono.just(42) else Mono.just(value) }
                )
            )
        )

        StepVerifier.create(gateway.list(listRequest())).expectNext("one").expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                causeCode.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
                cause.assert().isNull()
                suppressed.toList().assert().isEmpty()
            }
        }.verify()
    }

    @Test
    fun `metrics record success failure and cancellation exactly once with allowlisted tags`() {
        val registry = SimpleMeterRegistry()
        val successBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        StepVerifier.create(
            QueryGatewayFactory.create(
                gatewayConfiguration(successBackend, meterRegistry = registry)
            ).count(countRequest())
        ).expectNext(1).verifyComplete()

        val failureBackend = RecordingQueryBackend(
            gatewayDescriptor()
        ).respondCount(Mono.error(IllegalStateException("secret")))
        StepVerifier.create(
            QueryGatewayFactory.create(
                gatewayConfiguration(failureBackend, meterRegistry = registry)
            ).count(countRequest())
        ).expectError(QueryException::class.java).verify()

        val cancelBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.never())
        StepVerifier.create(
            QueryGatewayFactory.create(
                gatewayConfiguration(cancelBackend, meterRegistry = registry)
            ).count(countRequest())
        ).expectSubscription().thenAwait(java.time.Duration.ofMillis(10)).thenCancel().verify()
        cancelBackend.cancellations.get().assert().isOne()

        val meters = registry.find("wow.query.gateway").counters()
        meters.sumOf { it.count() }.assert().isEqualTo(3.0)
        meters.mapNotNull { it.id.getTag("outcome") }.toSet().assert()
            .isEqualTo(setOf("success", "failure", "cancel"))
        meters.flatMap { meter -> meter.id.tags.map { it.key } }.toSet().assert().isEqualTo(
            setOf(
                "operation",
                "documentKind",
                "backendId",
                "outcome",
                "errorCode",
                "capabilityId",
                "policyDescriptor",
                "legacyFacade"
            )
        )
    }

    @Test
    fun `policy failure metrics identify the failing system or custom registration`() {
        val systemRegistry = SimpleMeterRegistry()
        val systemBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val systemGateway = QueryGatewayFactory.create(
            gatewayConfiguration(systemBackend, meterRegistry = systemRegistry)
        )
        val deletedRequest = CountQueryRequest(
            target = GATEWAY_TARGET,
            requestedScope = RequestedQueryScope(deletion = DeletionScope.DELETED)
        )

        StepVerifier.create(systemGateway.count(deletedRequest)).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.POLICY_DENIED)
                message.orEmpty().contains("system").assert().isFalse()
            }
        }.verify()
        systemRegistry.get("wow.query.gateway")
            .tag("policyDescriptor", "system")
            .tag("outcome", "failure")
            .counter().count().assert().isEqualTo(1.0)
        systemBackend.countSubscriptions.get().assert().isZero()

        val customRegistry = SimpleMeterRegistry()
        val customBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val secondPolicyCalls = AtomicInteger()
        val customGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                customBackend,
                customPolicies = listOf(
                    QueryPolicy { Mono.error(IllegalStateException("secret-policy-error")) },
                    QueryPolicy {
                        secondPolicyCalls.incrementAndGet()
                        Mono.just(QueryPolicyResult())
                    }
                ),
                meterRegistry = customRegistry
            )
        )

        StepVerifier.create(customGateway.count(countRequest())).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                message.orEmpty().contains("custom-0").assert().isFalse()
                message.orEmpty().contains("secret-policy-error").assert().isFalse()
            }
        }.verify()
        customRegistry.get("wow.query.gateway")
            .tag("policyDescriptor", "custom-0")
            .tag("outcome", "failure")
            .counter().count().assert().isEqualTo(1.0)
        secondPolicyCalls.get().assert().isZero()
        customBackend.countSubscriptions.get().assert().isZero()
    }

    @Test
    fun `policy failure descriptor cardinality remains bounded`() {
        val failureRegistry = SimpleMeterRegistry()
        val failureBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val calls = AtomicInteger()
        val failureGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                failureBackend,
                customPolicies = listOf(
                    QueryPolicy {
                        Mono.error(IllegalStateException("secret-${calls.incrementAndGet()}"))
                    }
                ),
                meterRegistry = failureRegistry
            )
        )
        repeat(32) {
            StepVerifier.create(failureGateway.count(countRequest()))
                .expectError(QueryException::class.java)
                .verify()
        }
        failureRegistry.find("wow.query.gateway").counters().let { counters ->
            counters.size.assert().isOne()
            counters.single().id.getTag("policyDescriptor").assert().isEqualTo("custom-0")
            counters.single().count().assert().isEqualTo(32.0)
        }
    }

    @Test
    fun `successful policies keep later failures and cancellation combined`() {
        val successRegistry = SimpleMeterRegistry()
        val successBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val successGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                successBackend,
                customPolicies = listOf(QueryPolicy { Mono.just(QueryPolicyResult()) }),
                meterRegistry = successRegistry
            )
        )
        StepVerifier.create(successGateway.count(countRequest())).expectNext(1).verifyComplete()
        successRegistry.get("wow.query.gateway")
            .tag("policyDescriptor", "combined")
            .tag("outcome", "success")
            .counter().count().assert().isEqualTo(1.0)

        val backendFailureRegistry = SimpleMeterRegistry()
        val backendFailure = RecordingQueryBackend(gatewayDescriptor()).respondCount(
            Mono.error(IllegalStateException("secret-backend-error"))
        )
        val backendFailureGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backendFailure,
                customPolicies = listOf(QueryPolicy { Mono.just(QueryPolicyResult()) }),
                meterRegistry = backendFailureRegistry
            )
        )
        StepVerifier.create(backendFailureGateway.count(countRequest()))
            .expectError(QueryException::class.java)
            .verify()
        backendFailureRegistry.get("wow.query.gateway")
            .tag("policyDescriptor", "combined")
            .tag("outcome", "failure")
            .counter().count().assert().isEqualTo(1.0)

        val cancellationRegistry = SimpleMeterRegistry()
        val cancellationBackend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val cancellationGateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                cancellationBackend,
                customPolicies = listOf(QueryPolicy { Mono.never() }),
                meterRegistry = cancellationRegistry
            )
        )
        StepVerifier.create(cancellationGateway.count(countRequest())).expectSubscription().thenCancel().verify()
        cancellationRegistry.get("wow.query.gateway")
            .tag("policyDescriptor", "combined")
            .tag("outcome", "cancel")
            .counter().count().assert().isEqualTo(1.0)
    }

    @Test
    fun `capability metrics bound unknown values to one unsupported tag`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingQueryBackend(gatewayDescriptor())
        val knownCapability = QueryCapabilityId("configured-full-text")
        val enabledCapabilities = mutableSetOf(knownCapability)
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                meterRegistry = registry,
                enabledCapabilities = enabledCapabilities
            )
        )
        enabledCapabilities.clear()

        repeat(32) { index ->
            StepVerifier.create(gateway.list(capabilityRequest(QueryCapabilityId("unknown-$index"))))
                .expectError(QueryException::class.java)
                .verify()
        }

        val unknownMeters = registry.find("wow.query.gateway").counters()
        unknownMeters.size.assert().isOne()
        unknownMeters.single().id.getTag("capabilityId").assert().isEqualTo("unsupported")
        unknownMeters.single().count().assert().isEqualTo(32.0)

        StepVerifier.create(gateway.list(capabilityRequest(knownCapability)))
            .expectError(QueryException::class.java)
            .verify()
        registry.find("wow.query.gateway").tag("capabilityId", knownCapability.value).counters().size.assert().isOne()

        val multipleRequest = ListQueryRequest(
            target = GATEWAY_TARGET,
            expression = LogicalExpression(
                LogicalOperator.AND,
                listOf(
                    fullText(knownCapability),
                    fullText(QueryCapabilityId("another-unknown"))
                )
            ),
            resultShape = GATEWAY_SHAPE
        )
        StepVerifier.create(gateway.list(multipleRequest)).expectError(QueryException::class.java).verify()
        registry.find("wow.query.gateway").tag("capabilityId", "multiple").counters().size.assert().isOne()
    }

    @Test
    fun `execution deadline cancels backend and records one terminal error`() {
        val registry = SimpleMeterRegistry()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.never())
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend, meterRegistry = registry))
        val request = me.ahoo.wow.api.query.gateway.CountQueryRequest(
            GATEWAY_TARGET,
            budget = me.ahoo.wow.api.query.gateway.QueryBudgetHint(
                timeout = java.time.Duration.ofMillis(100)
            )
        )

        StepVerifier.create(gateway.count(request)).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
        }.verify(java.time.Duration.ofSeconds(2))

        backend.cancellations.get().assert().isOne()
        registry.find("wow.query.gateway").tag("outcome", "failure").counter()!!.count().assert().isEqualTo(1.0)
    }
    private fun capabilityRequest(capabilityId: QueryCapabilityId): ListQueryRequest<String> = ListQueryRequest(
        target = GATEWAY_TARGET,
        expression = fullText(capabilityId),
        resultShape = GATEWAY_SHAPE
    )

    private fun fullText(capabilityId: QueryCapabilityId): FullTextExpression = FullTextExpression(
        capabilityId = capabilityId,
        query = "query",
        fields = setOf(GATEWAY_STATUS)
    )
}
