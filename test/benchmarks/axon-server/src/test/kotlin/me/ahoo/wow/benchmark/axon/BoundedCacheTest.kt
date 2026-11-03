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

import org.axonframework.common.caching.Cache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.function.Supplier
import java.util.function.UnaryOperator

class BoundedCacheTest {
    /** What a listener heard, in order: `read a=1`, `created a=1`, `updated a=2`, `removed a`, `expired a`. */
    private class Heard : Cache.EntryListenerAdapter() {
        val events = mutableListOf<String>()

        override fun onEntryExpired(key: Any) {
            events += "expired $key"
        }

        override fun onEntryRemoved(key: Any) {
            events += "removed $key"
        }

        override fun onEntryUpdated(key: Any, value: Any) {
            events += "updated $key=$value"
        }

        override fun onEntryCreated(key: Any, value: Any) {
            events += "created $key=$value"
        }

        override fun onEntryRead(key: Any, value: Any) {
            events += "read $key=$value"
        }
    }

    private fun cache(capacity: Int = 10): Pair<BoundedCache, Heard> {
        val cache = BoundedCache(capacity)
        val heard = Heard()
        cache.registerCacheEntryListener(heard)
        return cache to heard
    }

    @Test
    fun `a put creates or updates an entry, and a get reads it`() {
        val (cache, heard) = cache()
        cache.put("a", 1)
        cache.put("a", 2)
        assertEquals(2, cache.get<String, Int>("a"))
        assertNull(cache.get<String, Int>("b"))
        assertTrue(cache.containsKey("a"))
        assertFalse(cache.containsKey("b"))
        assertEquals(listOf("created a=1", "updated a=2", "read a=2"), heard.events)
    }

    @Test
    fun `a put of null removes the entry`() {
        val (cache, heard) = cache()
        cache.put("a", 1)
        cache.put("a", null)
        assertFalse(cache.containsKey("a"))
        assertEquals(listOf("created a=1", "removed a"), heard.events)
    }

    @Test
    fun `putIfAbsent puts only an absent key`() {
        val (cache, heard) = cache()
        assertTrue(cache.putIfAbsent("a", 1))
        assertFalse(cache.putIfAbsent("a", 2))
        assertEquals(1, cache.get<String, Int>("a"))
        assertEquals(listOf("created a=1", "read a=1"), heard.events)
    }

    @Test
    fun `computeIfAbsent supplies an absent value once and refuses null`() {
        val (cache, heard) = cache()
        assertEquals(1, cache.computeIfAbsent("a", Supplier { 1 }))
        assertEquals(1, cache.computeIfAbsent("a", Supplier { 2 }))
        assertThrows(IllegalArgumentException::class.java) { cache.computeIfAbsent("b", Supplier<Int?> { null }) }
        assertFalse(cache.containsKey("b"))
        assertEquals(listOf("created a=1", "read a=1"), heard.events)
    }

    @Test
    fun `computeIfPresent updates a present entry, removes it on null and skips an absent key`() {
        val (cache, heard) = cache()
        cache.put("a", 1)
        cache.computeIfPresent("a", UnaryOperator<Int> { it + 1 })
        assertEquals(2, cache.get<String, Int>("a"))
        cache.computeIfPresent("a", UnaryOperator<Int?> { null })
        assertFalse(cache.containsKey("a"))
        cache.computeIfPresent("b", UnaryOperator<Int> { it + 1 })
        assertFalse(cache.containsKey("b"))
        assertEquals(listOf("created a=1", "updated a=2", "read a=2", "removed a"), heard.events)
    }

    @Test
    fun `remove and removeAll tell of each entry they remove`() {
        val (cache, heard) = cache()
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        assertTrue(cache.remove("a"))
        assertFalse(cache.remove("a"))
        cache.removeAll()
        assertFalse(cache.containsKey("b") || cache.containsKey("c"))
        assertEquals(listOf("created a=1", "created b=2", "created c=3", "removed a"), heard.events.take(4))
        // removeAll removes in the map's order.
        assertEquals(setOf("removed b", "removed c"), heard.events.drop(4).toSet())
        assertEquals(0, cache.size)
    }

    @Test
    fun `past its capacity it evicts the entry put first, a read or an update leaving its place`() {
        val (cache, heard) = cache(capacity = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get<String, Int>("a")
        cache.put("a", 10)
        cache.put("c", 3)
        assertFalse(cache.containsKey("a"))
        assertTrue(cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
        assertEquals(
            listOf("created a=1", "created b=2", "read a=1", "updated a=10", "created c=3", "expired a"),
            heard.events,
        )
    }

    @Test
    fun `it counts the reads that hit and those that missed`() {
        val (cache, _) = cache()
        cache.put("a", 1)
        cache.get<String, Int>("a")
        cache.get<String, Int>("a")
        cache.get<String, Int>("b")
        assertEquals(2L, cache.hits)
        assertEquals(1L, cache.misses)
        assertEquals(1, cache.size)
    }

    @Test
    fun `concurrent puts, reads and removals lose no event and keep within the capacity`() {
        val cache = BoundedCache(1_000)
        val created = LongAdder()
        val gone = LongAdder()
        cache.registerCacheEntryListener(
            object : Cache.EntryListenerAdapter() {
                override fun onEntryCreated(key: Any, value: Any) = created.increment()

                override fun onEntryExpired(key: Any) = gone.increment()

                override fun onEntryRemoved(key: Any) = gone.increment()
            },
        )
        val threads = 8
        val keys = 5_000
        val operations = 20_000
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { worker ->
            Thread {
                try {
                    start.await()
                    repeat(operations) { i ->
                        val key = (i * 31 + worker * 7) % keys
                        when (i % 4) {
                            0, 1 -> cache.put(key, i)
                            2 -> cache.get<Int, Int>(key)
                            else -> if (i % 40 == 3) cache.remove(key) else cache.putIfAbsent(key, i)
                        }
                    }
                } catch (failure: Throwable) {
                    failures += failure
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertTrue(workers.none { it.isAlive }, "a worker did not finish")
        assertTrue(failures.isEmpty(), "$failures")
        assertTrue(cache.size <= 1_000, "${cache.size} entries")
        // Every entry created was evicted, removed or is still there.
        assertEquals(cache.size.toLong(), created.sum() - gone.sum())
        assertEquals(cache.size, (0 until keys).count { cache.containsKey(it) })
        assertEquals(threads * operations / 4L, cache.hits + cache.misses)
    }

    @Test
    fun `a cancelled listener hears nothing more`() {
        val cache = BoundedCache(10)
        val heard = Heard()
        val registration = cache.registerCacheEntryListener(heard)
        cache.put("a", 1)
        assertTrue(registration.cancel())
        cache.put("b", 2)
        assertEquals(listOf("created a=1"), heard.events)
    }

    @Test
    fun `the capacity must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { BoundedCache(0) }
    }
}
