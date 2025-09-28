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

private const val MAX_RETRIES = 10L
private val MIN_BACKOFF = Duration.ofMillis(500)

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
