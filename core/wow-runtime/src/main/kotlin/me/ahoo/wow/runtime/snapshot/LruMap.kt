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

package me.ahoo.wow.runtime.snapshot

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** At most [capacity] entries, evicting the least recently used; safe to use from any thread. */
internal class LruMap<K : Any, V : Any>(private val capacity: Int) {
    private val lock = ReentrantLock()
    private val entries = object : LinkedHashMap<K, V>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
    }

    init {
        require(capacity >= 1) { "An LRU map holds at least one entry." }
    }

    val size: Int get() = lock.withLock { entries.size }

    operator fun get(key: K): V? = lock.withLock { entries[key] }

    operator fun set(key: K, value: V) {
        lock.withLock { entries[key] = value }
    }

    /** Removes and returns the value of [key] when [predicate] holds for it. */
    fun removeIf(key: K, predicate: (V) -> Boolean): V? = lock.withLock {
        entries[key]?.takeIf(predicate)?.also { entries.remove(key) }
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}
