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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.WowSpi
import reactor.core.publisher.Flux
import reactor.util.retry.Retry
import java.time.Duration

/**
 * How a [Transport] reacts when its receive stream fails, the same for every backend.
 *
 * The receive stream ([TransportReceiver.records]) is subscribed again after each failure, with [receiveRetry]'s
 * backoff. Only once the retries are exhausted does the failure reach the dispatcher, which reports it to the runtime.
 * Retries count consecutive failures: a record received in between starts the count again (transient errors).
 *
 * The default retries 3 times from a 10 s backoff, which is what the Kafka receiver did before 9.3.0; Redis Streams
 * retried nothing before 9.3.0.
 */
@WowSpi
class TransportFailurePolicy(
    val receiveRetry: Retry = receiveRetry(),
) {
    companion object {
        private val log = KotlinLogging.logger {}

        const val DEFAULT_RECEIVE_RETRY_ATTEMPTS: Long = 3
        val DEFAULT_RECEIVE_RETRY_BACKOFF: Duration = Duration.ofSeconds(10)

        @JvmField
        val DEFAULT: TransportFailurePolicy = TransportFailurePolicy()

        /**
         * Exponential backoff from [minBackoff], at most [maxAttempts] consecutive retries.
         */
        fun receiveRetry(
            maxAttempts: Long = DEFAULT_RECEIVE_RETRY_ATTEMPTS,
            minBackoff: Duration = DEFAULT_RECEIVE_RETRY_BACKOFF,
        ): Retry {
            require(maxAttempts >= 0) {
                "maxAttempts must not be negative."
            }
            require(!minBackoff.isNegative) {
                "minBackoff must not be negative."
            }
            return Retry.backoff(maxAttempts, minBackoff)
                .transientErrors(true)
                .doBeforeRetry { signal ->
                    log.warn(signal.failure()) {
                        "Retry the transport receive stream: consecutive retry ${signal.totalRetriesInARow() + 1} " +
                            "of $maxAttempts."
                    }
                }
        }
    }

    /**
     * [records], subscribed again on failure according to [receiveRetry].
     */
    fun <T : Any> retryReceive(records: Flux<T>): Flux<T> = records.retryWhen(receiveRetry)
}
