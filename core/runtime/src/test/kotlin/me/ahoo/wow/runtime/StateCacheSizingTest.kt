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

/** Work package B.O-4: each context's state cache sized from the heap when the host does not size it. */
class StateCacheSizingTest {
    private val gib = 1L shl 30

    @Test
    fun `a tenth of the heap over the estimated entry, split between the contexts`() {
        StateCacheSizing.entryBytes(256).assert().isEqualTo(1_024L + 256 * 12)
        // 4 GiB: 429 MB over 4096 bytes is 104857 states for one context, half of it for two.
        StateCacheSizing.capacity(contexts = 1, recentRequests = 256, maxHeap = 4 * gib).assert().isEqualTo(104_857)
        StateCacheSizing.capacity(contexts = 2, recentRequests = 256, maxHeap = 4 * gib).assert().isEqualTo(52_428)
        // Fewer recent requests, smaller entries: more of them.
        StateCacheSizing.capacity(contexts = 1, recentRequests = 0, maxHeap = 1 * gib).assert().isEqualTo(104_857)
    }

    @Test
    fun `a small heap keeps the former default, a large one the cap`() {
        StateCacheSizing.capacity(contexts = 1, recentRequests = 256, maxHeap = 256L shl 20).assert().isEqualTo(6_553)
        StateCacheSizing.capacity(contexts = 1, recentRequests = 256, maxHeap = 64L shl 20)
            .assert().isEqualTo(StateCacheSizing.MIN)
        StateCacheSizing.capacity(contexts = 8, recentRequests = 256, maxHeap = 4 * gib).assert().isEqualTo(13_107)
        StateCacheSizing.capacity(contexts = 1, recentRequests = 256, maxHeap = 64 * gib)
            .assert().isEqualTo(StateCacheSizing.MAX)
        StateCacheSizing.capacity(contexts = 1, recentRequests = 256)
            .assert().isBetween(StateCacheSizing.MIN, StateCacheSizing.MAX)
        assertThrows<IllegalArgumentException> { StateCacheSizing.capacity(contexts = 0, recentRequests = 256) }
        assertThrows<IllegalArgumentException> { StateCacheSizing.capacity(contexts = 1, recentRequests = -1) }
    }

    @Test
    fun `the cache reports each state it evicts`() {
        var evicted = 0
        val cache = LruStateCache(capacity = 2) { evicted++ }
        val type = AggregateType("test", "counter")
        (1..5).forEach { cache.put(type.ref("t", "a-$it"), CachedState(it, 1)) }
        evicted.assert().isEqualTo(3)
        cache.size.assert().isEqualTo(2)
    }
}
