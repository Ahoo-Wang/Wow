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

package me.ahoo.wow.reactor

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.recoverable
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RetryTest {
    companion object {
        private val log = LoggerFactory.getLogger(RetryTest::class.java)
    }

    private fun getRetryStrategy(): Retry {
        return Retry.backoff(3, Duration.ofMillis(100))
            .filter {
                it.recoverable == RecoverableType.RECOVERABLE
            }.doBeforeRetry {
                if (log.isWarnEnabled) {
                    log.warn(
                        "Retry totalRetries[{}] - finished.",
                        it.totalRetries(),
                        it.failure()
                    )
                }
            }
    }

    @Test
    fun retry() {
        val retryStrategy = getRetryStrategy()
        Mono.error<String>(TimeoutException("test"))
            .retryWhen(retryStrategy)
            .doOnError {
                log.error("error", it)
            }
            .test()
            .verifyError()
        log.info("-------")
        Mono.error<String>(TimeoutException("test"))
            .retryWhen(retryStrategy)
            .doOnError {
                log.error("error", it)
            }
            .test()
            .verifyError()
    }

    @Test
    fun retry2() {
        val retryStrategy = getRetryStrategy()
        val counter = AtomicInteger(0)
        Mono.fromRunnable<Void> {
            val count = counter.addAndGet(1)
            if (count < 4) {
                throw TimeoutException("test")
            }
        }
            .retryWhen(retryStrategy)
            .doOnError {
                log.error("error", it)
            }
            .test()
            .verifyComplete()
    }
}
