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

package me.ahoo.wow.redis.bus

import java.time.Duration

data class RedisStreamRecoveryOptions(
    val enabled: Boolean = true,
    val minIdleTime: Duration = Duration.ofMinutes(5),
    val interval: Duration = Duration.ofSeconds(30),
    val batchSize: Long = 100
) {
    init {
        require(minIdleTime >= MIN_DURATION) {
            "minIdleTime must be at least 1 millisecond."
        }
        require(interval >= MIN_DURATION) {
            "interval must be at least 1 millisecond."
        }
        require(batchSize > 0) {
            "batchSize must be positive."
        }
    }

    companion object {
        private val MIN_DURATION = Duration.ofMillis(1)

        @JvmField
        val DEFAULT: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions()

        @JvmField
        val DISABLED: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions(enabled = false)
    }
}
