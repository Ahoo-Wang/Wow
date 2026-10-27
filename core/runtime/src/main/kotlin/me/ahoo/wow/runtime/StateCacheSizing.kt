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

package me.ahoo.wow.runtime

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.CachedState

/**
 * How much each context's state cache keeps when the host does not bound it by a number of states
 * (`wow.state-cache.capacity`): a [HEAP_SHARE] of the JVM's maximum heap, split between the node's
 * contexts, in the [estimated bytes][CachedState.bytes] of the states it holds. A cached state is the aggregate's
 * state object with its metadata, estimated at [CachedState.STATE_BYTES], and its recent requests,
 * whose rings grow with the requests the aggregate committed: most aggregates commit few, so the bytes hold far more
 * of them than a count sized for full rings would. A domain whose states are much larger than the estimate sets the
 * capacity itself.
 *
 * The budget is at least [MIN] states with full rings, the former fixed default.
 */
@InternalWowApi
object StateCacheSizing {
    /** The share of the maximum heap the state caches of a node take together. */
    const val HEAP_SHARE: Double = 0.1

    /** The fewest states with full rings a context's cache holds when bounded by bytes. */
    const val MIN: Int = 4_096

    /** The estimated bytes of one cached state whose rings hold [recentRequests] requests. */
    fun entryBytes(recentRequests: Int): Long = CachedState.STATE_BYTES + recentRequests * CachedState.REQUEST_BYTES

    /**
     * The estimated bytes each of [contexts] state caches holds on a heap of at most [maxHeap] bytes, each state
     * keeping up to [recentRequests] recent requests.
     */
    fun maxBytes(contexts: Int, recentRequests: Int, maxHeap: Long = Runtime.getRuntime().maxMemory()): Long {
        require(contexts >= 1) { "A node serves at least one context." }
        require(recentRequests >= 0) { "The recent requests kept per aggregate are never negative." }
        return maxOf((maxHeap * HEAP_SHARE).toLong() / contexts, MIN * entryBytes(recentRequests))
    }
}
