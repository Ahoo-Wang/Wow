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
    fun `a tenth of the heap, split between the contexts`() {
        StateCacheSizing.entryBytes(256).assert().isEqualTo(1_024L + 256 * 12)
        StateCacheSizing.maxBytes(contexts = 1, recentRequests = 256, maxHeap = 4 * gib).assert().isEqualTo(429_496_729)
        StateCacheSizing.maxBytes(contexts = 2, recentRequests = 256, maxHeap = 4 * gib).assert().isEqualTo(214_748_364)
    }

    @Test
    fun `a small heap still holds the former default of full states`() {
        val floor = StateCacheSizing.MIN * StateCacheSizing.entryBytes(256)
        StateCacheSizing.maxBytes(contexts = 1, recentRequests = 256, maxHeap = 64L shl 20).assert().isEqualTo(floor)
        StateCacheSizing.maxBytes(contexts = 8, recentRequests = 256, maxHeap = 1 * gib).assert().isEqualTo(floor)
        StateCacheSizing.maxBytes(contexts = 1, recentRequests = 0, maxHeap = 1L shl 20)
            .assert().isEqualTo(StateCacheSizing.MIN * CachedState.STATE_BYTES)
        assertThrows<IllegalArgumentException> { StateCacheSizing.maxBytes(contexts = 0, recentRequests = 256) }
        assertThrows<IllegalArgumentException> { StateCacheSizing.maxBytes(contexts = 1, recentRequests = -1) }
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
