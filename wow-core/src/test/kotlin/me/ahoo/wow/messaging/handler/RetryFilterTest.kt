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

import io.github.oshai.kotlinlogging.KLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

internal class RetryFilterTest {

    @Test
    fun retryStrategyWithDefaults() {
        val logger = mockk<KLogger>()
        every { logger.warn(any<Throwable>(), any<() -> Any?>()) } returns Unit

        val retry = retryStrategy(logger = logger)

        Mono.error<String>(RuntimeException("Test exception"))
            .retryWhen(retry)
            .test()
            .verifyTimeout(Duration.ofSeconds(10))
    }

    @Test
    fun retryStrategyWithCustomValues() {
        val logger = mockk<KLogger>()
        every { logger.warn(any<Throwable>(), any<() -> Any?>()) } returns Unit
        val maxAttempts = 3L
        val minBackoff = Duration.ofMillis(100)

        val retry = retryStrategy(
            maxAttempts = maxAttempts,
            minBackoff = minBackoff,
            logger = logger
        )

        Mono.error<String>(RuntimeException("Test exception"))
            .retryWhen(retry)
            .test()
            .verifyError()

        // Should retry 3 times (custom maxAttempts)
        verify(exactly = 3) { logger.warn(any<Throwable>(), any<() -> Any?>()) }
    }

    @Test
    fun retryStrategyWithSuccessAfterRetry() {
        val logger = mockk<KLogger>()
        every { logger.warn(any<Throwable>(), any<() -> Any?>()) } returns Unit

        val retry = retryStrategy(maxAttempts = 5, logger = logger)
        val counter = AtomicInteger(0)

        Mono.fromCallable {
            if (counter.getAndIncrement() < 2) {
                @Suppress("TooGenericExceptionThrown")
                throw RuntimeException("Test exception on attempt ${counter.get()}")
            }
            "success"
        }
            .retryWhen(retry)
            .test()
            .expectNext("success")
            .verifyComplete()

        // Should retry 2 times before success
        verify(exactly = 2) { logger.warn(any<Throwable>(), any<() -> Any?>()) }
    }
}
