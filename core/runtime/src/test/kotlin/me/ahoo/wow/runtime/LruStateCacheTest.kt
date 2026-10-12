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
}
