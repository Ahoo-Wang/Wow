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
import kotlin.math.pow

interface NextRetryAtCalculator {
    fun nextRetryAt(
        minBackoff: Int,
        retries: Int,
        currentRetryAt: Long = System.currentTimeMillis()
    ): Long {
        val multiple = 2.0.pow(retries.toDouble()).toLong()
        val nextRetryDuration = minBackoff * multiple * 1000
        return currentRetryAt + nextRetryDuration
    }

    fun nextRetryState(
        retrySpec: IRetrySpec,
        retries: Int,
        retryAt: Long = System.currentTimeMillis()
    ): RetryState {
        val nextRetryAt = nextRetryAt(retrySpec.minBackoff, retries, retryAt)
        val timoutAt = retryAt + retrySpec.executionTimeout * 1000
        return RetryState(
            retries = retries,
            retryAt = retryAt,
            timoutAt = timoutAt,
            nextRetryAt = nextRetryAt,
        )
    }
}

object DefaultNextRetryAtCalculator : NextRetryAtCalculator
