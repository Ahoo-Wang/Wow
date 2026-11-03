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

package me.ahoo.wow.benchmark.axon

import org.axonframework.common.Registration
import org.axonframework.common.caching.Cache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.function.Supplier
import java.util.function.UnaryOperator

/**
 * The account repository's cache: strong references, at most [capacity] entries. Axon's
 * [org.axonframework.common.caching.WeakReferenceCache] loses its entries at every collection, so it rarely hits, and
 * Axon's strong-reference caches adapt EhCache or JCache, third-party libraries; Wow's state cache holds strong
 * references too. It keeps Axon's cache contract, as `WeakReferenceCache` does: a listener hears of every read of an
 * entry, its creation, its update, its removal and its eviction (`onEntryExpired`).
 *
 * A [ConcurrentHashMap] holds the entries, so a read takes no lock: a lock every command's load took would be a
 * bottleneck of the benchmark's own making at thousands of concurrent commands. Past [capacity] the entries put first
 * leave first, as [order] keeps them; a key removed and put again keeps its first place there, so the order is
 * approximate. The comparison's capacity is above its data set, so nothing is evicted there.
 */
class BoundedCache(private val capacity: Int) : Cache {
    private val entries = ConcurrentHashMap<Any, Any>()

    /** The keys in the order they were put, oldest first, with those removed since (cleared past [capacity]). */
    private val order = ConcurrentLinkedQueue<Any>()
    private val removedSince = AtomicInteger()
    private val listeners = CopyOnWriteArrayList<Cache.EntryListener>()
    private val hitCount = LongAdder()
    private val missCount = LongAdder()

    init {
        require(capacity > 0) { "The capacity must be positive: $capacity" }
    }

    /** The reads that found an entry. */
    val hits: Long get() = hitCount.sum()

    /** The reads that found none. */
    val misses: Long get() = missCount.sum()

    /** The entries held. */
    val size: Int get() = entries.size

    override fun <K, V> get(key: K): V? {
        val value = entries[key as Any]
        if (value == null) {
            missCount.increment()
            return null
        }
        hitCount.increment()
        listeners.forEach { it.onEntryRead(key, value) }
        @Suppress("UNCHECKED_CAST")
        return value as V
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        if (entries.put(key, value) == null) {
            created(key, value)
        } else {
            listeners.forEach { it.onEntryUpdated(key, value) }
        }
    }

    override fun putIfAbsent(key: Any, value: Any): Boolean {
        val absent = entries.putIfAbsent(key, value) == null
        if (absent) created(key, value)
        return absent
    }

    override fun <T> computeIfAbsent(key: Any, valueSupplier: Supplier<T>): T {
        get<Any, T>(key)?.let { return it }
        val value = requireNotNull(valueSupplier.get()) {
            "Value Supplier of Cache produced a null value for key [$key]!"
        }
        put(key, value)
        return value
    }

    override fun remove(key: Any): Boolean {
        val removed = entries.remove(key) != null
        if (removed) removed(key)
        return removed
    }

    override fun removeAll() {
        entries.keys.forEach { key -> remove(key) }
    }

    override fun containsKey(key: Any): Boolean = entries.containsKey(key)

    override fun <V> computeIfPresent(key: Any, update: UnaryOperator<V>) {
        var present = false
        val updated = entries.computeIfPresent(key) { _, current ->
            present = true
            @Suppress("UNCHECKED_CAST")
            update.apply(current as V)
        }
        when {
            !present -> return
            updated == null -> removed(key)
            else -> listeners.forEach { it.onEntryUpdated(key, updated) }
        }
    }

    override fun registerCacheEntryListener(cacheEntryListener: Cache.EntryListener): Registration {
        listeners += cacheEntryListener
        return Registration { listeners.remove(cacheEntryListener) }
    }

    private fun created(key: Any, value: Any) {
        order += key
        listeners.forEach { it.onEntryCreated(key, value) }
        evict()
    }

    private fun removed(key: Any) {
        listeners.forEach { it.onEntryRemoved(key) }
        // A removed key stays in the order until it comes up or there are too many such: then the order is rebuilt.
        if (removedSince.incrementAndGet() > capacity) {
            synchronized(order) {
                if (removedSince.get() > capacity) {
                    order.clear()
                    order.addAll(entries.keys)
                    removedSince.set(0)
                }
            }
        }
    }

    /** Evicts the entries put first while the cache holds more than its capacity. */
    private fun evict() {
        while (entries.size > capacity) {
            val eldest = order.poll() ?: return
            if (entries.remove(eldest) != null) listeners.forEach { it.onEntryExpired(eldest) }
        }
    }
}
