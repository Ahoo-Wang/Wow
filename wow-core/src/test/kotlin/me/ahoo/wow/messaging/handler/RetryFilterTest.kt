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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RetryFilterTest {

    @Test
    fun `retryStrategy resubscribes until the source succeeds`() {
        val attempts = AtomicInteger()

        StepVerifier.withVirtualTime {
            val retry = deterministicRetry(maxAttempts = 2)
            Mono.defer {
                if (attempts.incrementAndGet() < 3) {
                    Mono.error(TimeoutException("recoverable"))
                } else {
                    Mono.just("done")
                }
            }.retryWhen(retry)
        }
            .thenAwait(Duration.ofSeconds(5))
            .expectNext("done")
            .verifyComplete()

        attempts.get().assert().isEqualTo(3)
    }

    @Test
    fun `retryStrategy propagates the terminal failure after retries are exhausted`() {
        val attempts = AtomicInteger()

        StepVerifier.withVirtualTime {
            val retry = deterministicRetry(maxAttempts = 1)
            Mono.defer<String> {
                attempts.incrementAndGet()
                Mono.error(TimeoutException("recoverable"))
            }.retryWhen(retry)
        }
            .thenAwait(Duration.ofSeconds(2))
            .expectError()
            .verify()

        attempts.get().assert().isEqualTo(2)
    }

    private companion object {
        val log = KotlinLogging.logger {}

        fun deterministicRetry(maxAttempts: Long): Retry {
            val retry = retryStrategy(
                maxAttempts = maxAttempts,
                minBackoff = Duration.ofSeconds(1),
                logger = log,
            )
            return (retry as RetryBackoffSpec).jitter(0.0)
        }
    }
}
