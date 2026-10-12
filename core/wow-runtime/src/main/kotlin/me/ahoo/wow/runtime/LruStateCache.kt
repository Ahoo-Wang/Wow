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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.StateCache
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [StateCache] of at most [capacity] aggregates, evicting the least recently used. Taking a state out (for a
 * command) frees its slot until the command puts it back.
 */
class LruStateCache(private val capacity: Int) : StateCache {
    private val lock = ReentrantLock()
    private val states = object : LinkedHashMap<AggregateRef, CachedState>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, CachedState>): Boolean =
            size > capacity
    }

    init {
        require(capacity >= 1) { "A state cache holds at least one state." }
    }

    val size: Int get() = lock.withLock { states.size }

    override fun take(ref: AggregateRef): CachedState? = lock.withLock { states.remove(ref) }

    override fun put(ref: AggregateRef, state: CachedState) {
        lock.withLock { states[ref] = state }
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}
