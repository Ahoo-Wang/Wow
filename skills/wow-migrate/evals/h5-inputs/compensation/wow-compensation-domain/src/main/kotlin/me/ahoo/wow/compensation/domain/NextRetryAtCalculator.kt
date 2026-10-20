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

import me.ahoo.wow.compensation.api.IRetrySpec
import me.ahoo.wow.compensation.api.RetryState

interface NextRetryAtCalculator {
    fun validate(retrySpec: IRetrySpec) {
        require(retrySpec.maxRetries >= 0) { "maxRetries must be greater than or equal to 0." }
        nextRetryState(retrySpec, retrySpec.maxRetries, retryAt = 0)
    }

    fun nextRetryAt(
        minBackoff: Int,
        retries: Int,
        currentRetryAt: Long = System.currentTimeMillis()
    ): Long {
        require(minBackoff >= 0) { "minBackoff must be greater than or equal to 0." }
        require(retries >= 0) { "retries must be greater than or equal to 0." }
        if (minBackoff == 0) {
            return currentRetryAt
        }
        if (retries >= Long.SIZE_BITS - 1) {
            throw ArithmeticException("Retry backoff exceeds the supported millisecond range.")
        }
        val multiplier = 1L shl retries
        val backoffSeconds = Math.multiplyExact(minBackoff.toLong(), multiplier)
        val backoffMillis = Math.multiplyExact(backoffSeconds, 1000L)
        return Math.addExact(currentRetryAt, backoffMillis)
    }

    fun nextRetryState(
        retrySpec: IRetrySpec,
        retries: Int,
        retryAt: Long = System.currentTimeMillis()
    ): RetryState {
        require(retrySpec.executionTimeout >= 0) {
            "executionTimeout must be greater than or equal to 0."
        }
        val nextRetryAt = nextRetryAt(retrySpec.minBackoff, retries, retryAt)
        val timeoutDuration = Math.multiplyExact(retrySpec.executionTimeout.toLong(), 1000L)
        val timeoutAt = Math.addExact(retryAt, timeoutDuration)
        return RetryState(
            retries = retries,
            retryAt = retryAt,
            timeoutAt = timeoutAt,
            nextRetryAt = nextRetryAt,
        )
    }
}

object DefaultNextRetryAtCalculator : NextRetryAtCalculator
