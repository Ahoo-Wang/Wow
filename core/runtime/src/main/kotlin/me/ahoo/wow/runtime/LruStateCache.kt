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
import me.ahoo.wow.kernel.StateSizes
import me.ahoo.wow.spi.log.Partitioner
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [StateCache] of at most [capacity] aggregates and [maxBytes] of their estimated size ([weigh]),
 * evicting the least recently used while either is passed. Taking a state out (for a command) frees its room until the
 * command puts it back, weighed again then. [invalidate] drops a partition's states when the node acquires or releases
 * it, scanning every stripe.
 *
 * A large cache is striped by aggregate: each of up to [Stripes.MAX] stripes is an LRU of its share of
 * the bounds under its own lock, so the commands of different aggregates rarely take the same lock. Eviction is then
 * least recently used within a stripe; a cache smaller than two stripes' minimum is one exact LRU.
 */
@InternalWowApi
class LruStateCache(
    private val capacity: Int = Int.MAX_VALUE,
    private val maxBytes: Long = Long.MAX_VALUE,
    /** The estimated bytes of a state, weighed when it is put: its class's measured size ([StateSizes]) on a node. */
    private val weigh: (CachedState) -> Long = CachedState::bytes,
    /** Called for each state evicted to make room, from the putting thread: a metric hook. */
    private val evicted: () -> Unit = {},
) : StateCache {
    init {
        require(capacity >= 1) { "A state cache holds at least one state." }
        require(maxBytes >= CachedState.STATE_BYTES) { "A state cache holds at least one state's bytes." }
        require(capacity < Int.MAX_VALUE || maxBytes < Long.MAX_VALUE) { "A state cache is bounded." }
    }

    /** Striped by the most states it may hold: its capacity, or as many as its bytes hold at their smallest. */
    private val stripes: Array<Stripe> =
        stripesOf(Stripes.of(minOf(capacity.toLong(), maxBytes / CachedState.STATE_BYTES).toInt()))

    private fun stripesOf(count: Int): Array<Stripe> = Array(count) { index ->
        Stripe(
            if (capacity == Int.MAX_VALUE) Int.MAX_VALUE else Stripes.share(capacity, count, index),
            if (maxBytes == Long.MAX_VALUE) Long.MAX_VALUE else maxBytes / count,
            evicted,
        )
    }

    val size: Int get() = stripes.sumOf { it.size }

    /** The estimated bytes of the states it holds. */
    val bytes: Long get() = stripes.sumOf { it.bytes }

    override fun take(ref: AggregateRef): CachedState? = Stripes.pick(stripes, ref).take(ref)

    override fun put(ref: AggregateRef, state: CachedState) {
        // Weighed outside the stripe's lock.
        Stripes.pick(stripes, ref).put(ref, Held(state, weigh(state)))
    }

    override fun invalidate(partition: Int, partitions: Int) {
        stripes.forEach { stripe -> stripe.removeIf { Partitioner.partitionOf(it, partitions) == partition } }
    }

    /** A state with the bytes it was weighed at when put. */
    private class Held(val state: CachedState, val bytes: Long)

    private class Stripe(private val capacity: Int, private val maxBytes: Long, private val evicted: () -> Unit) {
        private val lock = ReentrantLock()
        private val states = LinkedHashMap<AggregateRef, Held>(INITIAL, LOAD_FACTOR, true)
        private var held = 0L

        val size: Int get() = lock.withLock { states.size }

        val bytes: Long get() = lock.withLock { held }

        fun take(ref: AggregateRef): CachedState? = lock.withLock {
            states.remove(ref)?.let { taken ->
                held -= taken.bytes
                taken.state
            }
        }

        fun put(ref: AggregateRef, weighed: Held) {
            lock.withLock {
                states.put(ref, weighed)?.let { replaced -> held -= replaced.bytes }
                held += weighed.bytes
                val eldest = states.entries.iterator()
                while ((states.size > capacity || held > maxBytes) && eldest.hasNext()) {
                    val entry = eldest.next()
                    // The state just put stays, the eldest is evicted first: it is the last in access order.
                    if (entry.key == ref) break
                    eldest.remove()
                    held -= entry.value.bytes
                    evicted()
                }
            }
        }

        fun removeIf(matches: (AggregateRef) -> Boolean) {
            lock.withLock {
                val all = states.entries.iterator()
                while (all.hasNext()) {
                    val entry = all.next()
                    if (matches(entry.key)) {
                        all.remove()
                        held -= entry.value.bytes
                    }
                }
            }
        }
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}

internal object Stripes {
    const val MAX: Int = 16
    const val MIN_SHARE: Int = 256

    fun of(capacity: Int): Int = (capacity / MIN_SHARE).coerceIn(1, MAX)

    /** The capacity of stripe [index] of [count]: [capacity] spread evenly, the remainder over the first ones. */
    fun share(capacity: Int, count: Int, index: Int): Int = capacity / count + if (index < capacity % count) 1 else 0

    fun <T> pick(stripes: Array<T>, ref: AggregateRef): T = stripes[Math.floorMod(ref.hashCode(), stripes.size)]
}
