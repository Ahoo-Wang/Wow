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

package me.ahoo.wow.benchmark.infrastructure

import java.time.Duration

data class StorageBatchTuningOptions(
    val maxSize: Int,
    val maxDelay: Duration,
) {
    companion object {
        private val FORMAT = Regex("""([1-9]\d*)x([1-9]\d*)us""")

        fun parse(value: String): StorageBatchTuningOptions {
            val match = requireNotNull(FORMAT.matchEntire(value)) {
                "Storage batch tuning options must use '<maxSize>x<maxDelayMicros>us': $value"
            }
            val maxSize = requireNotNull(match.groupValues[1].toIntOrNull()) {
                "Storage batch maxSize is out of range: $value"
            }
            val maxDelayMicros = requireNotNull(match.groupValues[2].toLongOrNull()) {
                "Storage batch maxDelay is out of range: $value"
            }
            val maxDelayNanos = try {
                Math.multiplyExact(maxDelayMicros, NANOS_PER_MICROSECOND)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Storage batch maxDelay is out of range: $value", error)
            }
            return StorageBatchTuningOptions(
                maxSize = maxSize,
                maxDelay = Duration.ofNanos(maxDelayNanos),
            )
        }

        private const val NANOS_PER_MICROSECOND: Long = 1_000
    }
}
