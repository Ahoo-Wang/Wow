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

package me.ahoo.wow.infra.batch

import java.time.Duration

/**
 * Storage-independent limits for [BatchCoordinator].
 *
 * Enabling batching is deliberately not part of this type. The component that
 * owns a coordinator decides whether to compose a direct or batched writer.
 */
data class BatchOptions(
    val maxSize: Int = DEFAULT_MAX_SIZE,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    /**
     * Bounds both live submissions and physical queue slots.
     *
     * A queued cancellation releases live admission immediately, but its
     * physical queue slot remains reserved until the batching pipeline observes
     * and discards the placeholder. A cancellation storm may therefore reject a
     * new submission even when fewer than `maxPendingItems` live callers remain;
     * this keeps the internal Reactor queue bounded.
     */
    val maxPendingItems: Int = DEFAULT_MAX_PENDING_ITEMS,
    val laneCount: Int = DEFAULT_LANE_COUNT,
) {
    init {
        require(maxSize > 1) {
            "maxSize must be greater than 1."
        }
        // Reactor fair buffering computes its prefetch as maxSize << 2.
        require(maxSize <= Int.MAX_VALUE / 4) {
            "maxSize must not exceed Int.MAX_VALUE / 4."
        }
        require(!maxDelay.isNegative && !maxDelay.isZero) {
            "maxDelay must be positive."
        }
        require(maxPendingItems >= maxSize) {
            "maxPendingItems must be greater than or equal to maxSize."
        }
        require(laneCount in 1..maxPendingItems) {
            "laneCount must be between 1 and maxPendingItems."
        }
    }

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 128
        val DEFAULT_MAX_DELAY: Duration = Duration.ofMillis(1)
        const val DEFAULT_MAX_PENDING_ITEMS: Int = 4096
        const val DEFAULT_LANE_COUNT: Int = 1
    }
}
