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
package me.ahoo.wow.kafka

import reactor.util.retry.Retry
import java.time.Duration

class KafkaReceiverPolicy(
    val prefetchBatches: Int = DEFAULT_PREFETCH_BATCHES,
    val maxDeferredCommits: Int = DEFAULT_MAX_DEFERRED_COMMITS,
    val retrySpec: Retry = defaultRetrySpec(),
) {
    init {
        require(prefetchBatches > 0) {
            "prefetchBatches must be greater than 0."
        }
        require(maxDeferredCommits > 0) {
            "maxDeferredCommits must be greater than 0 to preserve out-of-order acknowledgements."
        }
    }

    companion object {
        const val DEFAULT_PREFETCH_BATCHES: Int = 1
        const val DEFAULT_MAX_DEFERRED_COMMITS: Int = 1
        const val DEFAULT_RETRY_ATTEMPTS: Long = 3
        val DEFAULT_RETRY_BACKOFF: Duration = Duration.ofSeconds(10)

        fun defaultRetrySpec(
            maxAttempts: Long = DEFAULT_RETRY_ATTEMPTS,
            minBackoff: Duration = DEFAULT_RETRY_BACKOFF,
        ): Retry {
            require(maxAttempts >= 0) {
                "maxAttempts must not be negative."
            }
            require(!minBackoff.isNegative) {
                "minBackoff must not be negative."
            }
            return Retry.backoff(maxAttempts, minBackoff)
                .transientErrors(true)
        }
    }
}
