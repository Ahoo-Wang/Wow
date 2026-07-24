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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.mod

/**
 * Utility object for calculating message ordering stripes.
 *
 * Provides the default stripe count and functions to compute stripe keys from
 * aggregate IDs. The stripe count is independent from Scheduler worker count.
 */
object MessageParallelism {
    /**
     * Default ordering-stripe count for message processing.
     *
     * Can be overridden by the "wow.parallelism" system property.
     * Defaults to 64 times the number of available processors.
     */
    val DEFAULT_PARALLELISM = System.getProperty("wow.parallelism")?.toInt()
        ?: (64 * Runtime.getRuntime().availableProcessors())

    /**
     * Computes an ordering-stripe key based on the aggregate ID.
     *
     * Uses modulo operation to distribute aggregate IDs across parallel groups.
     *
     * @param parallelism The number of ordering stripes (default: DEFAULT_PARALLELISM)
     * @return An integer key between 0 and parallelism-1
     */
    fun AggregateIdCapable.toGroupKey(parallelism: Int = DEFAULT_PARALLELISM): Int = aggregateId.mod(parallelism)
}

interface ParallelismCapable {
    val parallelism: Int
}
