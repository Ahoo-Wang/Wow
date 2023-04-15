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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.exception.retryable
import me.ahoo.wow.command.ServerCommandExchange
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal class RetryableFilterTest {

    @Test
    fun filter() {
        val retryableFilter = RetryableFilter<ServerCommandExchange<Any>>()
        val exchange = mockk<ServerCommandExchange<Any>>()

        val chain = mockk<FilterChain<ServerCommandExchange<Any>>>()
        every {
            chain.filter(exchange)
        } returns Mono.empty()

        retryableFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }

    @Test
    fun filterGivenTimeout() {
        val retryableFilter = RetryableFilter<ServerCommandExchange<Any>>(
            Retry.backoff(3, Duration.ofMillis(100))
                .filter { it.retryable },
        )

        val exchange = mockk<ServerCommandExchange<Any>>()
        val chain = mockk<FilterChain<ServerCommandExchange<Any>>>()
        every {
            chain.filter(exchange)
        } returns Mono.error(TimeoutException())

        retryableFilter.filter(exchange, chain)
            .test()
            .consumeErrorWith {
                assertThat(Exceptions.isRetryExhausted(it), equalTo(true))
            }
            .verify()
    }

    @Test
    fun filterGivenTimeoutNextSuccess() {
        val retryableFilter = RetryableFilter<ServerCommandExchange<Any>>(
            Retry.backoff(3, Duration.ofMillis(100))
                .filter { it.retryable },
        )
        val exchange = mockk<ServerCommandExchange<Any>>()
        val chain = mockk<FilterChain<ServerCommandExchange<Any>>>()
        val counter = AtomicInteger(0)
        every {
            chain.filter(exchange)
        } returns Mono.defer {
            if (counter.getAndIncrement() < 2) {
                Mono.error(TimeoutException())
            } else {
                Mono.empty()
            }
        }

        retryableFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }
}
