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

package me.ahoo.wow.benchmark.workload

private const val NANOS_PER_SECOND: Long = 1_000_000_000

/**
 * Arithmetic-only fixed-rate arrival plan.
 *
 * Global sequence numbers are partitioned by producer index without allocating an
 * arrival list. Each sequence retains one globally scheduled timestamp, so a delayed
 * producer cannot silently turn the workload into a completion-paced closed loop.
 */
class OpenLoopArrivalPlan(
    val ratePerSecond: Long,
    val producerCount: Int,
    val durationNanos: Long,
) {
    val arrivalCount: Long

    init {
        require(ratePerSecond > 0) {
            "ratePerSecond must be greater than 0."
        }
        require(producerCount > 0) {
            "producerCount must be greater than 0."
        }
        require(durationNanos > 0) {
            "durationNanos must be greater than 0."
        }
        val scaledDuration = runCatching {
            Math.multiplyExact(ratePerSecond, durationNanos)
        }.getOrElse {
            throw IllegalArgumentException("ratePerSecond × durationNanos must not overflow.", it)
        }
        arrivalCount = ceilDiv(scaledDuration, NANOS_PER_SECOND)
        require(arrivalCount <= Long.MAX_VALUE / NANOS_PER_SECOND) {
            "The arrival sequence is too large to retain nanosecond timestamps."
        }
    }

    fun firstSequence(producerIndex: Int): Long {
        require(producerIndex in 0 until producerCount) {
            "producerIndex must be between 0 and ${producerCount - 1}."
        }
        return producerIndex.toLong()
    }

    fun nextSequence(sequence: Long): Long =
        Math.addExact(sequence, producerCount.toLong())

    fun contains(sequence: Long): Boolean =
        sequence >= 0 && sequence < arrivalCount

    fun scheduledOffsetNanos(sequence: Long): Long {
        require(sequence >= 0) {
            "sequence must not be negative."
        }
        return Math.multiplyExact(sequence, NANOS_PER_SECOND) / ratePerSecond
    }

    private fun ceilDiv(
        dividend: Long,
        divisor: Long,
    ): Long =
        if (dividend == 0L) {
            0L
        } else {
            ((dividend - 1) / divisor) + 1
        }
}
