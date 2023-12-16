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

package me.ahoo.wow.compensation.domain

import me.ahoo.wow.compensation.api.RetryState
import reactor.util.retry.RetrySpec
import java.time.Duration
import kotlin.math.pow

/**
 * 补偿重试规范
 *
 *
 * @see RetrySpec.backoff
 */
interface CompensationSpec {

    val maxRetries: Int

    /**
     * the minimum Duration for the first backoff
     *
     */
    val minBackoff: Duration

    /**
     * 补偿执行超时时间
     *
     */
    val executionTimeout: Duration

    fun nextRetryAt(retries: Int, currentRetryAt: Long = System.currentTimeMillis()): Long {
        val multiple = 2.0.pow(retries.toDouble()).toLong()
        val nextRetryDuration = minBackoff.toMillis() * multiple
        return currentRetryAt + nextRetryDuration
    }

    fun nextRetryState(retries: Int, retryAt: Long = System.currentTimeMillis()): RetryState {
        val nextRetryAt = nextRetryAt(retries, retryAt)
        val timoutAt = retryAt + executionTimeout.toMillis()
        return RetryState(
            maxRetries = maxRetries,
            retries = retries,
            retryAt = retryAt,
            timoutAt = timoutAt,
            nextRetryAt = nextRetryAt,
        )
    }
}

object DefaultCompensationSpec : CompensationSpec {
    override val maxRetries: Int
        get() = 10
    override val minBackoff: Duration
        get() = Duration.ofSeconds(180)
    override val executionTimeout: Duration
        get() = Duration.ofSeconds(120)
}
