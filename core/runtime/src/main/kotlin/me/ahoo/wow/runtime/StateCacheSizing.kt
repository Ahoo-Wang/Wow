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

/**
 * How many aggregate states each context's state cache keeps when the host does not say (`wow.state-cache.capacity`,
 * work package B.O-4): a [HEAP_SHARE] of the JVM's maximum heap, split between the node's contexts, over the
 * [estimated][entryBytes] size of one cached state, within [MIN] and [MAX].
 *
 * The cache is the write path's biggest lever: a miss costs a snapshot read and a tail read before the command is
 * decided. A cached state is the aggregate's state object, its metadata and its recent requests (decision D261,
 * 12 bytes each); a domain whose states are much larger than [STATE_BYTES] sets the capacity itself.
 */
@InternalWowApi
object StateCacheSizing {
    /** The share of the maximum heap the state caches of a node take together. */
    const val HEAP_SHARE: Double = 0.1

    /** The estimated bytes of one cached state object with its metadata, its recent requests aside. */
    const val STATE_BYTES: Long = 1_024

    /** The bytes one recent request takes: its 64-bit hash and its version. */
    const val REQUEST_BYTES: Long = 12

    /** The fewest states a context's cache keeps when sized from the heap: the former fixed default. */
    const val MIN: Int = 4_096

    /** The most states a context's cache keeps when sized from the heap. */
    const val MAX: Int = 131_072

    /** The estimated bytes of one cached state keeping [recentRequests] recent requests. */
    fun entryBytes(recentRequests: Int): Long = STATE_BYTES + recentRequests * REQUEST_BYTES

    /**
     * The capacity of each of [contexts] state caches on a heap of at most [maxHeap] bytes, each state keeping
     * [recentRequests] recent requests.
     */
    fun capacity(contexts: Int, recentRequests: Int, maxHeap: Long = Runtime.getRuntime().maxMemory()): Int {
        require(contexts >= 1) { "A node serves at least one context." }
        require(recentRequests >= 0) { "The recent requests kept per aggregate are never negative." }
        val states = (maxHeap * HEAP_SHARE).toLong() / entryBytes(recentRequests) / contexts
        return states.coerceIn(MIN.toLong(), MAX.toLong()).toInt()
    }
}
