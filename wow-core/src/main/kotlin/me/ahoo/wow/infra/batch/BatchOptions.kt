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
    val maxSize: Int,
    val maxDelay: Duration,
    /**
     * Bounds both live submissions and physical queue slots.
     *
     * A queued cancellation releases live admission immediately, but its
     * physical queue slot remains reserved until the batching pipeline observes
     * and discards the placeholder. A cancellation storm may therefore reject a
     * new submission even when fewer than [maxPendingItems] live callers remain;
     * this keeps the internal Reactor queue bounded.
     */
    val maxPendingItems: Int,
) {
    init {
        require(maxSize > 1) {
            "maxSize must be greater than 1."
        }
        require(!maxDelay.isNegative && !maxDelay.isZero) {
            "maxDelay must be positive."
        }
        require(maxPendingItems >= maxSize) {
            "maxPendingItems must be greater than or equal to maxSize."
        }
    }
}
