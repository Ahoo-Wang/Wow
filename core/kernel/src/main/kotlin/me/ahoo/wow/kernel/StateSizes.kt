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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.ConcurrentHashMap

/**
 * The measured size of the states of each class, for a state cache bounded by bytes: the mean length
 * of their JSON as the [EventCodec] that measures them writes it (the state views and snapshots) and reads it (the
 * snapshots loaded), over about its last [WINDOW] states.
 *
 * A cached state weighs [ENTRY_BYTES] (its entry, reference, metadata and the cache's node), [TEXT_FACTOR] bytes for
 * each character of its class's mean JSON, and what its recent requests' rings take. A class not measured yet weighs
 * [CachedState.bytes], the fixed estimate: a domain that has no snapshots and no state readers is weighed as before.
 * Each state is weighed by its class's mean, not its own size, so the cache's total follows the states' while each
 * weighing stays a lookup.
 */
@InternalWowApi
class StateSizes {
    private val means = ConcurrentHashMap<Class<*>, Mean>()

    /** A state of [type] written or read as [length] characters of JSON. */
    fun record(type: Class<*>, length: Int) {
        means.computeIfAbsent(type) { Mean() }.add(length)
    }

    /** The mean JSON length of the states of [type], or null before one is measured. */
    fun meanLength(type: Class<*>): Long? = means[type]?.value?.toLong()

    /** The estimated bytes [cached] takes in a cache. */
    fun bytesOf(cached: CachedState): Long {
        val length = meanLength(cached.state.javaClass) ?: return cached.bytes
        return ENTRY_BYTES + TEXT_FACTOR * length + (cached.requests?.bytes ?: 0)
    }

    /**
     * A running mean that weighs about the last [WINDOW] values most; concurrent adds may lose one, which an estimate
     * allows.
     */
    private class Mean {
        @Volatile
        var value: Double = 0.0
            private set

        @Volatile
        private var count = 0

        fun add(length: Int) {
            val n = minOf(count + 1, WINDOW)
            value += (length - value) / n
            count = n
        }
    }

    @InternalWowApi
    companion object {
        /**
         * The bytes of a cached state's entry beside its state object: the [CachedState], its reference and type, its
         * first commit and scope, the cache's node and the id's string (about 340 measured, 2026-10-24).
         */
        const val ENTRY_BYTES: Long = 384

        /** The heap bytes a state object takes for each character of its JSON, generously (strings, boxes, headers). */
        const val TEXT_FACTOR: Long = 2

        /** About how many of the latest states of a class its mean follows. */
        const val WINDOW: Int = 1_024
    }
}
