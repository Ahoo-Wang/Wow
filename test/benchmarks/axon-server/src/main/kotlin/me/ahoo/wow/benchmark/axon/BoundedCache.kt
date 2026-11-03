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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import java.util.function.UnaryOperator

/**
 * The account repository's cache: strong references, at most [capacity] entries, the least recently used evicted
 * first. Axon's [org.axonframework.common.caching.WeakReferenceCache] loses its entries at every collection, so it
 * rarely hits, and Axon's strong-reference caches adapt EhCache or JCache, third-party libraries; Wow's state cache
 * holds strong references too. It keeps Axon's cache contract, as `WeakReferenceCache` does: a listener hears of every
 * read of an entry, its creation, its update, its removal and its eviction (`onEntryExpired`). One lock guards the
 * entries; the listeners are told after it is released.
 */
class BoundedCache(private val capacity: Int) : Cache {
    private val listeners = CopyOnWriteArrayList<Cache.EntryListener>()
    private val evicted = mutableListOf<Any>()
    private val entries = object : LinkedHashMap<Any, Any>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Any>?): Boolean =
            (size > capacity).also { evict -> if (evict && eldest != null) evicted += eldest.key }
    }

    init {
        require(capacity > 0) { "The capacity must be positive: $capacity" }
    }

    override fun <K, V> get(key: K): V? {
        val value = synchronized(entries) { entries[key as Any] } ?: return null
        listeners.forEach { it.onEntryRead(key, value) }
        @Suppress("UNCHECKED_CAST")
        return value as V
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        val (previous, expired) = locked { entries.put(key, value) }
        listeners.forEach {
            if (previous == null) it.onEntryCreated(key, value) else it.onEntryUpdated(key, value)
        }
        expire(expired)
    }

    override fun putIfAbsent(key: Any, value: Any): Boolean {
        val (previous, expired) = locked { entries.putIfAbsent(key, value) }
        if (previous == null) listeners.forEach { it.onEntryCreated(key, value) }
        expire(expired)
        return previous == null
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
        val removed = synchronized(entries) { entries.remove(key) } != null
        if (removed) listeners.forEach { it.onEntryRemoved(key) }
        return removed
    }

    override fun removeAll() {
        val keys = synchronized(entries) { entries.keys.toList().also { entries.clear() } }
        keys.forEach { key -> listeners.forEach { it.onEntryRemoved(key) } }
    }

    override fun containsKey(key: Any): Boolean = synchronized(entries) { entries.containsKey(key) }

    override fun <V> computeIfPresent(key: Any, update: UnaryOperator<V>) {
        // An absent key returns at once; null from the update removes the entry.
        val updated: V? = synchronized(entries) {
            @Suppress("UNCHECKED_CAST")
            val current = entries[key] as V? ?: return
            val next: V? = update.apply(current)
            if (next == null) {
                entries.remove(key)
            } else {
                entries[key] = next
            }
            next
        }
        listeners.forEach { if (updated == null) it.onEntryRemoved(key) else it.onEntryUpdated(key, updated) }
    }

    override fun registerCacheEntryListener(cacheEntryListener: Cache.EntryListener): Registration {
        listeners += cacheEntryListener
        return Registration { listeners.remove(cacheEntryListener) }
    }

    /** Runs [action] under the lock, with the keys it evicted. */
    private fun <R> locked(action: () -> R): Pair<R, List<Any>> = synchronized(entries) {
        val result = action()
        val expired = evicted.toList()
        evicted.clear()
        result to expired
    }

    private fun expire(keys: List<Any>) {
        keys.forEach { key -> listeners.forEach { it.onEntryExpired(key) } }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
        const val LOAD_FACTOR = 0.75f
    }
}
