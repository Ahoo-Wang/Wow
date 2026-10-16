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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.StateCache
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [StateCache] of at most [capacity] aggregates, evicting the least recently used. Taking a state out (for a
 * command) frees its slot until the command puts it back.
 *
 * A large cache is striped by aggregate (review A5-3): each of up to [Stripes.MAX] stripes is an LRU of its share of
 * the capacity under its own lock, so the commands of different aggregates rarely take the same lock. Eviction is
 * then least recently used within a stripe; a cache smaller than two stripes' minimum is one exact LRU.
 */
@InternalWowApi
class LruStateCache(private val capacity: Int) : StateCache {
    init {
        require(capacity >= 1) { "A state cache holds at least one state." }
    }

    private val stripes: Array<Stripe> = Stripes.of(capacity).let { count ->
        Array(count) { index -> Stripe(Stripes.share(capacity, count, index)) }
    }

    val size: Int get() = stripes.sumOf { it.size }

    override fun take(ref: AggregateRef): CachedState? = Stripes.pick(stripes, ref).take(ref)

    override fun put(ref: AggregateRef, state: CachedState) {
        Stripes.pick(stripes, ref).put(ref, state)
    }

    private class Stripe(private val capacity: Int) {
        private val lock = ReentrantLock()
        private val states = object : LinkedHashMap<AggregateRef, CachedState>(INITIAL, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, CachedState>): Boolean =
                size > capacity
        }

        val size: Int get() = lock.withLock { states.size }

        fun take(ref: AggregateRef): CachedState? = lock.withLock { states.remove(ref) }

        fun put(ref: AggregateRef, state: CachedState) {
            lock.withLock { states[ref] = state }
        }
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * How a per-context structure keyed by aggregate is striped (review A5-3): [of] gives the stripes of a [capacity],
 * at most [MAX] of at least [MIN_SHARE] each, so a small one stays one stripe with exact bounds.
 */
internal object Stripes {
    const val MAX: Int = 16
    const val MIN_SHARE: Int = 256

    fun of(capacity: Int): Int = (capacity / MIN_SHARE).coerceIn(1, MAX)

    /** The capacity of stripe [index] of [count]: [capacity] spread evenly, the remainder over the first ones. */
    fun share(capacity: Int, count: Int, index: Int): Int = capacity / count + if (index < capacity % count) 1 else 0

    fun <T> pick(stripes: Array<T>, ref: AggregateRef): T = stripes[Math.floorMod(ref.hashCode(), stripes.size)]
}
