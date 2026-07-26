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

package me.ahoo.wow.elasticsearch.eventsourcing

import java.time.Duration

/**
 * Controls transparent batching for [ElasticsearchEventStore] appends.
 *
 * Batching is opt-in because collecting a partial batch adds up to [maxDelay]
 * to a low-throughput append.
 */
data class ElasticsearchEventStoreBatchOptions(
    val enabled: Boolean = false,
    val maxSize: Int = DEFAULT_MAX_SIZE,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val maxPendingAppends: Int = DEFAULT_MAX_PENDING_APPENDS,
) {
    init {
        require(maxSize > 1) {
            "maxSize must be greater than 1."
        }
        require(!maxDelay.isNegative && !maxDelay.isZero) {
            "maxDelay must be positive."
        }
        require(maxPendingAppends >= maxSize) {
            "maxPendingAppends must be greater than or equal to maxSize."
        }
    }

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 128
        const val DEFAULT_MAX_PENDING_APPENDS: Int = 4096
        val DEFAULT_MAX_DELAY: Duration = Duration.ofMillis(1)
    }
}
