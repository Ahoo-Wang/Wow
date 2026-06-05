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

package me.ahoo.wow.messaging.handler

import me.ahoo.test.asserts.assert
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.messaging.TestNamedMessage
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RetryableExchangeFilterBehaviorTest {

    @Test
    fun `filter retries next chain until it completes`() {
        val attempts = AtomicInteger()
        val filter = RetryableFilter<RetryableExchange>(Retry.max(2))
        val exchange = RetryableExchange()
        val chain = FilterChain<RetryableExchange> {
            Mono.defer {
                if (attempts.incrementAndGet() < 3) {
                    Mono.error(IllegalStateException("retry"))
                } else {
                    Mono.empty()
                }
            }
        }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        attempts.get().assert().isEqualTo(3)
    }

    @Test
    fun `filter propagates error when retry is exhausted`() {
        val attempts = AtomicInteger()
        val filter = RetryableFilter<RetryableExchange>(Retry.max(1))
        val chain = FilterChain<RetryableExchange> {
            Mono.defer<Void> {
                attempts.incrementAndGet()
                Mono.error(IllegalStateException("retry"))
            }
        }

        StepVerifier.create(filter.filter(RetryableExchange(), chain))
            .expectError()
            .verify()

        attempts.get().assert().isEqualTo(2)
    }

    @Test
    fun `default filter retries recoverable errors`() {
        val attempts = AtomicInteger()
        val filter = RetryableFilter<RetryableExchange>()
        val chain = FilterChain<RetryableExchange> {
            Mono.defer {
                if (attempts.incrementAndGet() == 1) {
                    Mono.error(TimeoutException("recoverable"))
                } else {
                    Mono.empty()
                }
            }
        }

        StepVerifier.withVirtualTime { filter.filter(RetryableExchange(), chain) }
            .thenAwait(Duration.ofSeconds(10))
            .verifyComplete()

        attempts.get().assert().isEqualTo(2)
    }

    @Test
    fun `default filter does not retry unrecoverable errors`() {
        val attempts = AtomicInteger()
        val filter = RetryableFilter<RetryableExchange>()
        val chain = FilterChain<RetryableExchange> {
            Mono.defer<Void> {
                attempts.incrementAndGet()
                Mono.error(IllegalStateException("unrecoverable"))
            }
        }

        StepVerifier.create(filter.filter(RetryableExchange(), chain))
            .expectError(IllegalStateException::class.java)
            .verify()

        attempts.get().assert().isEqualTo(1)
    }
}

private class RetryableExchange : MessageExchange<RetryableExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    override val message: TestNamedMessage = TestNamedMessage()
}
