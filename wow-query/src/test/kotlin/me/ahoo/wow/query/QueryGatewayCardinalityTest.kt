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
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.query.backend.RecordingQueryBackend
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.result.ResultPolicy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher
import java.time.Duration

class QueryGatewayCardinalityTest {
    @Test
    fun `result policy rejects a second item without waiting for completion`() {
        val publisher = TestPublisher.create<Any>()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.just("backend"))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                resultPolicies = listOf(ResultPolicy { _, _ -> Mono.fromDirect(publisher) }),
            ),
        )

        StepVerifier.create(gateway.single(singleRequest()))
            .then { publisher.next("one", "two") }
            .expectErrorSatisfies(::assertResultInvalid)
            .verify(Duration.ofSeconds(1))
        publisher.assertCancelled()
    }

    @Test
    fun `policy rejects a second item without waiting for completion`() {
        val publisher = TestPublisher.create<QueryPolicyResult>()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.just(1))
        val gateway = QueryGatewayFactory.create(
            gatewayConfiguration(
                backend,
                customPolicies = listOf(QueryPolicy { Mono.fromDirect(publisher) }),
            ),
        )

        StepVerifier.create(gateway.count(countRequest()))
            .then { publisher.next(QueryPolicyResult(), QueryPolicyResult()) }
            .expectErrorSatisfies { error ->
                (error as QueryException).apply {
                    code.assert().isEqualTo(QueryErrorCode.POLICY_FAILURE)
                    stage.assert().isEqualTo(QueryStage.POLICY)
                }
            }
            .verify(Duration.ofSeconds(1))
        publisher.assertCancelled()
        backend.countSubscriptions.get().assert().isZero()
    }

    @Test
    fun `single rejects a second backend item without waiting for completion`() {
        val publisher = TestPublisher.create<String>()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondSingle(Mono.fromDirect(publisher))
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend))

        StepVerifier.create(gateway.single(singleRequest()))
            .then { publisher.next("one", "two") }
            .expectErrorSatisfies(::assertResultInvalid)
            .verify(Duration.ofSeconds(1))
        publisher.assertCancelled()
    }

    @Test
    fun `count rejects a second backend item without waiting for completion`() {
        val publisher = TestPublisher.create<Long>()
        val backend = RecordingQueryBackend(gatewayDescriptor()).respondCount(Mono.fromDirect(publisher))
        val gateway = QueryGatewayFactory.create(gatewayConfiguration(backend))

        StepVerifier.create(gateway.count(countRequest()))
            .then { publisher.next(1, 2) }
            .expectErrorSatisfies(::assertResultInvalid)
            .verify(Duration.ofSeconds(1))
        publisher.assertCancelled()
    }

    private fun assertResultInvalid(error: Throwable) {
        (error as QueryException).code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
    }
}
