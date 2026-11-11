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

package me.ahoo.wow.http.internal

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The tuning of an [HttpSignalSender]:
 *
 * - [queueCapacity]: the signals each origin's queue holds; one more is dropped.
 * - [concurrency]: the requests sent from one origin's queue at a time, its share of the streams of the one
 *   connection to the origin ([InternalHttp.MAX_CONCURRENT_STREAMS]).
 * - [idleTimeout]: how long an origin's queue lives without signals.
 * - [maxBatch]: the most signals one request carries to an origin.
 * - [retryBackoff]: the wait before a failed request is sent again the first time, doubled each time after, up to
 *   [HttpSignalSender.MAX_RETRY_BACKOFF].
 * - [maxAttempts]: the most times a request is sent, the first included.
 */
@InternalWowApi
class HttpSignalSenderOptions(
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    val maxBatch: Int = DEFAULT_MAX_BATCH,
    val retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(queueCapacity >= 1) { "A signal queue holds at least one signal." }
        require(concurrency >= 1) { "At least one signal is sent at a time." }
        require(idleTimeout.isPositive()) { "The idle timeout is positive." }
        require(maxBatch >= 1) { "A request carries at least one signal." }
        require(retryBackoff.isPositive()) { "The retry backoff is positive." }
        require(maxAttempts >= 1) { "A request is sent at least once." }
    }

    @InternalWowApi
    companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 1_024
        const val DEFAULT_CONCURRENCY: Int = 16
        val DEFAULT_IDLE_TIMEOUT: Duration = 1.minutes
        const val DEFAULT_MAX_BATCH: Int = 64

        /** The wait before a failed request is sent again the first time. */
        val DEFAULT_RETRY_BACKOFF: Duration = 50.milliseconds

        /** The most times a request is sent, the first included: 50 + 100 + 200 + 400 ms of backoff in all. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
    }
}
