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

package me.ahoo.wow.messaging.transport

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class TransportFailurePolicyTest {

    @Test
    fun `the default retries three consecutive failures from a ten second backoff`() {
        val retry = TransportFailurePolicy.DEFAULT.receiveRetry as RetryBackoffSpec

        retry.maxAttempts.assert().isEqualTo(3)
        retry.minBackoff.assert().isEqualTo(Duration.ofSeconds(10))
        retry.isTransientErrors.assert().isTrue()
    }

    @Test
    fun `invalid retry settings are rejected`() {
        assertThrows<IllegalArgumentException> {
            TransportFailurePolicy.receiveRetry(maxAttempts = -1)
        }.message.assert().isEqualTo("maxAttempts must not be negative.")
        assertThrows<IllegalArgumentException> {
            TransportFailurePolicy.receiveRetry(minBackoff = Duration.ofNanos(-1))
        }.message.assert().isEqualTo("minBackoff must not be negative.")
    }

    @Test
    fun `a failed receive stream is subscribed again and continues`() {
        val subscriptions = AtomicInteger()
        val records = Flux.defer {
            if (subscriptions.incrementAndGet() < 3) {
                Flux.error(IllegalStateException("broker blip"))
            } else {
                Flux.just("record")
            }
        }

        StepVerifier.withVirtualTime {
            TransportFailurePolicy(TransportFailurePolicy.receiveRetry(minBackoff = Duration.ofSeconds(1)))
                .retryReceive(records)
        }
            .thenAwait(Duration.ofMinutes(1))
            .expectNext("record")
            .verifyComplete()

        subscriptions.get().assert().isEqualTo(3)
    }

    @Test
    fun `the failure escalates once the retries are exhausted`() {
        val subscriptions = AtomicInteger()
        val records = Flux.defer<String> {
            subscriptions.incrementAndGet()
            Flux.error(IllegalStateException("broker down"))
        }

        StepVerifier.withVirtualTime {
            TransportFailurePolicy(
                TransportFailurePolicy.receiveRetry(maxAttempts = 2, minBackoff = Duration.ofSeconds(1))
            )
                .retryReceive(records)
        }
            .thenAwait(Duration.ofMinutes(1))
            .expectErrorSatisfies { error ->
                Exceptions.isRetryExhausted(error).assert().isTrue()
                error.cause!!.message.assert().isEqualTo("broker down")
            }
            .verify()

        subscriptions.get().assert().isEqualTo(3)
    }

    @Test
    fun `a record in between starts the retry count again`() {
        val subscriptions = AtomicInteger()
        // Every subscription delivers one record, then fails: transient errors never exhaust one retry.
        val records = Flux.defer {
            val subscription = subscriptions.incrementAndGet()
            if (subscription < 5) {
                Flux.just("record-$subscription").concatWith(Flux.error(IllegalStateException("blip")))
            } else {
                Flux.just("record-$subscription")
            }
        }

        StepVerifier.withVirtualTime {
            TransportFailurePolicy(
                TransportFailurePolicy.receiveRetry(maxAttempts = 1, minBackoff = Duration.ofSeconds(1))
            )
                .retryReceive(records)
        }
            .thenAwait(Duration.ofMinutes(1))
            .expectNext("record-1", "record-2", "record-3", "record-4", "record-5")
            .verifyComplete()
    }
}
