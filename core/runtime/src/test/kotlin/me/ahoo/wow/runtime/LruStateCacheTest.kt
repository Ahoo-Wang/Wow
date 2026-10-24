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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.CachedState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LruStateCacheTest {
    private val type = AggregateType("test", "counter")

    @Test
    fun `the least recently used state is evicted first`() {
        val cache = LruStateCache(capacity = 2)
        cache.put(type.ref("t", "a"), CachedState("a", 1))
        cache.put(type.ref("t", "b"), CachedState("b", 1))
        cache.put(type.ref("t", "a"), cache.take(type.ref("t", "a"))!!)
        cache.put(type.ref("t", "c"), CachedState("c", 1))
        cache.take(type.ref("t", "b")).assert().isNull()
        cache.take(type.ref("t", "a"))!!.state.assert().isEqualTo("a")
    }

    @Test
    fun `a taken state is no longer cached`() {
        val cache = LruStateCache(capacity = 2)
        cache.put(type.ref("t", "a"), CachedState("a", 1))
        cache.take(type.ref("t", "a")).assert().isNotNull()
        cache.take(type.ref("t", "a")).assert().isNull()
        cache.size.assert().isEqualTo(0)
    }

    @Test
    fun `a cache bounded by bytes evicts the least recently used past its bytes`() {
        val one = CachedState("a", 1).bytes
        var evicted = 0
        val cache = LruStateCache(maxBytes = 2 * one) { evicted++ }
        cache.put(type.ref("t", "a"), CachedState("a", 1))
        cache.put(type.ref("t", "b"), CachedState("b", 1))
        cache.bytes.assert().isEqualTo(2 * one)
        cache.put(type.ref("t", "a"), cache.take(type.ref("t", "a"))!!)
        cache.put(type.ref("t", "c"), CachedState("c", 1))
        evicted.assert().isEqualTo(1)
        cache.take(type.ref("t", "b")).assert().isNull()
        cache.take(type.ref("t", "a"))!!.state.assert().isEqualTo("a")
        cache.bytes.assert().isEqualTo(one)
    }

    @Test
    fun `putting a state again weighs it once`() {
        val cache = LruStateCache(maxBytes = 4 * CachedState.STATE_BYTES)
        repeat(3) { cache.put(type.ref("t", "a"), CachedState(it, 1)) }
        cache.size.assert().isEqualTo(1)
        cache.bytes.assert().isEqualTo(CachedState.STATE_BYTES)
        cache.invalidate(0, 1)
        cache.size.assert().isZero()
        cache.bytes.assert().isZero()
    }

    @Test
    fun `a cache is bounded by a capacity, bytes or both`() {
        assertThrows<IllegalArgumentException> { LruStateCache() }
        assertThrows<IllegalArgumentException> { LruStateCache(capacity = 0) }
        assertThrows<IllegalArgumentException> { LruStateCache(maxBytes = CachedState.STATE_BYTES - 1) }
        val both = LruStateCache(capacity = 2, maxBytes = 1_000 * CachedState.STATE_BYTES)
        repeat(5) { both.put(type.ref("t", "a-$it"), CachedState(it, 1)) }
        both.size.assert().isEqualTo(2)
    }

    @Test
    fun `a large cache bounded by bytes is striped within its bytes`() {
        val cache = LruStateCache(maxBytes = 1_000 * CachedState.STATE_BYTES)
        repeat(5_000) { cache.put(type.ref("t", "a-$it"), CachedState(it, 1)) }
        cache.bytes.assert().isLessThanOrEqualTo(1_000 * CachedState.STATE_BYTES)
        cache.size.assert().isGreaterThan(900)
        cache.take(type.ref("t", "a-4999"))!!.state.assert().isEqualTo(4_999)
    }

    @Test
    fun `a large cache is striped, each stripe bounded, the whole within its capacity`() {
        Stripes.of(2).assert().isEqualTo(1)
        Stripes.of(4_096).assert().isEqualTo(Stripes.MAX)
        Stripes.of(1_000).assert().isEqualTo(3)
        (0 until 3).sumOf { Stripes.share(1_000, 3, it) }.assert().isEqualTo(1_000)
        val cache = LruStateCache(capacity = 1_000)
        repeat(5_000) { cache.put(type.ref("t", "a-$it"), CachedState(it, 1)) }
        cache.size.assert().isLessThanOrEqualTo(1_000)
        cache.size.assert().isGreaterThan(900)
        // The most recent states stay, whichever stripe they are in.
        cache.take(type.ref("t", "a-4999"))!!.state.assert().isEqualTo(4_999)
    }

    @Test
    fun `threads putting and taking different aggregates at once keep every state`() {
        val cache = LruStateCache(capacity = 4_096)
        val threads = (0 until THREADS).map { thread ->
            Thread {
                repeat(PER_THREAD) { n ->
                    val ref = type.ref("t", "$thread-${n % 64}")
                    val taken = cache.take(ref)
                    cache.put(ref, CachedState((taken?.state as Int? ?: 0) + 1, 1))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        cache.size.assert().isEqualTo(THREADS * 64)
        val total = (0 until THREADS).sumOf { thread ->
            (0 until 64).sumOf { cache.take(type.ref("t", "$thread-$it"))!!.state as Int }
        }
        total.assert().isEqualTo(THREADS * PER_THREAD)
    }

    private companion object {
        const val THREADS = 8
        const val PER_THREAD = 2_000
    }
}
