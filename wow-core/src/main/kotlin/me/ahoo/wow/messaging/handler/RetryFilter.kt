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
import reactor.util.retry.Retry
import java.time.Duration

/**
 * Maximum number of retry attempts.
 */
private const val MAX_RETRIES = 10L

/**
 * Minimum backoff duration between retries.
 */
private val MIN_BACKOFF = Duration.ofMillis(200)

/**
 * Creates a retry strategy with exponential backoff and logging.
 *
 * @param maxAttempts Maximum number of retry attempts (default: 10)
 * @param minBackoff Minimum backoff duration between retries (default: 200ms)
 * @param logger Logger to use for retry notifications
 * @return A configured Retry instance
 */
fun retryStrategy(
    maxAttempts: Long = MAX_RETRIES,
    minBackoff: Duration = MIN_BACKOFF,
    logger: KLogger
): Retry {
    return Retry.backoff(
        maxAttempts,
        minBackoff
    ).doBeforeRetry {
        logger.warn(it.failure()) {
            "[BeforeRetry] totalRetries[${it.totalRetries()}]."
        }
    }
}
