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
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.validation.QueryBudgetLimit
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewayPolicyConsistencyTest {
    @Test
    fun `factory snapshots custom and result policy lists`() {
        val queryPolicyCalls = AtomicInteger()
        val resultPolicyCalls = AtomicInteger()
        val policies = mutableListOf(
            QueryPolicy {
                queryPolicyCalls.incrementAndGet()
                Mono.just(QueryPolicyResult())
            }
        )
        val resultPolicies = mutableListOf(
            ResultPolicy { _, value ->
                resultPolicyCalls.incrementAndGet()
                Mono.just(value)
            }
        )
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.just("value"))
        val configuration = gatewayConfiguration(backend, policies, resultPolicies)
        val gateway = QueryGatewayFactory.create(configuration)
        policies += QueryPolicy { Mono.error(AssertionError("late policy")) }
        resultPolicies += ResultPolicy { _, _ -> Mono.error(AssertionError("late result policy")) }

        StepVerifier.create(gateway.single(singleRequest())).expectNext("value").verifyComplete()

        queryPolicyCalls.get().assert().isOne()
        resultPolicyCalls.get().assert().isOne()
        configuration.customPolicies.size.assert().isOne()
        configuration.resultPolicies.size.assert().isOne()
    }

    @Test
    fun `empty or wrong typed result policy output is result validation failure`() {
        val cases = listOf(
            ResultPolicy { _, _ -> Mono.empty() },
            ResultPolicy { _, _ -> Mono.just(42) }
        )

        cases.forEach { policy ->
            val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.just("value"))
            val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend, resultPolicies = listOf(policy)))

            StepVerifier.create(gateway.single(singleRequest())).expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
            }.verify()
        }
    }

    @Test
    fun `zero admission deadline and tighter backend deadline fail before execution`() {
        val immediateBackend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.just("value"))
        val immediateGateway = QueryGatewayFactory.create(gatewayConfiguration(immediateBackend))
        val immediateRequest = SingleQueryRequest(
            target = GATEWAY_TARGET,
            resultShape = GATEWAY_SHAPE,
            budget = QueryBudgetHint(timeout = Duration.ZERO)
        )

        StepVerifier.create(immediateGateway.single(immediateRequest)).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
        }.verify()
        immediateBackend.singleSubscriptions.get().assert().isZero()

        val tighterBackend = RecordingQueryBackend(
            gatewayDescriptor(QueryBudgetLimit(timeout = Duration.ZERO))
        ).respondSingle(Mono.just("value"))
        val tighterGateway = QueryGatewayFactory.create(gatewayConfiguration(tighterBackend))
        StepVerifier.create(tighterGateway.single(singleRequest())).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
        }.verify()
        tighterBackend.singleSubscriptions.get().assert().isZero()
    }

    @Test
    fun `public gateway surface does not expose provenance injection`() {
        QueryGateway::class.java.methods.map { it.name }.filter { it in setOf("single", "list", "page", "count") }
            .toSet().assert().isEqualTo(setOf("single", "list", "page", "count"))
        QueryGateway::class.java.methods.flatMap { it.parameterTypes.toList() }.none {
            it.name.contains("QueryProvenance")
        }.assert().isTrue()
    }
}
