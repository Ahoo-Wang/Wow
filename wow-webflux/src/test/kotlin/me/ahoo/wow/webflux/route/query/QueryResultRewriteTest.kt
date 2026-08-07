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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.route.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.gateway.QueryExecutionException
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class QueryResultRewriteTest {
    @Test
    fun `mono transformer cannot bypass the gateway source`() {
        val subscriptions = AtomicInteger()
        val source = Mono.defer {
            subscriptions.incrementAndGet()
            Mono.just("gateway")
        }

        StepVerifier.create(source.rewriteResultOneToOne(rewrite = { Mono.just("rewritten") }))
            .expectNext("rewritten")
            .verifyComplete()

        subscriptions.get().assert().isEqualTo(1)
    }

    @Test
    fun `flux transformer cannot change cardinality`() {
        StepVerifier.create(Flux.just("gateway").rewriteResultOneToOne { Flux.just("one", "two") })
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QueryExecutionException::class.java)
                (error as QueryExecutionException).code.assert().isEqualTo("RESULT_REWRITE_CONTRACT_VIOLATION")
            }
            .verify()
    }

    @Test
    fun `mono transformer must preserve page envelope invariants`() {
        StepVerifier.create(
            Mono.just("original").rewriteResultOneToOne(
                rewrite = { Mono.just("rewritten") },
                validate = { original, rewritten -> original == rewritten },
            ),
        )
            .expectError(QueryExecutionException::class.java)
            .verify()
    }
}
