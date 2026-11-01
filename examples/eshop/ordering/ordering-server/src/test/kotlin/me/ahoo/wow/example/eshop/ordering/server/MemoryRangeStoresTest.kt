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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.cosid.RangeExhaustedException
import me.ahoo.cosid.segment.Range
import me.ahoo.cosid.store.StoreConfig
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The order numbers' ranges in the service's memory, as CosId's segment generator reserves them. */
class MemoryRangeStoresTest {
    private val provider = MemoryRangeStores()
    private val store = provider.open(StoreConfig.builder("orders", MemoryRangeStores.TYPE).build())
    private val ranges = store.ranges()

    @AfterEach
    fun close() {
        store.close()
    }

    @Test
    fun `the provider should be CosId's store type memory`() {
        provider.type().assert().isEqualTo("memory")
    }

    @Test
    fun `a key's ranges should start at its initial offset, follow each other and never overlap`() {
        ranges.reserve(KEY, 100, 1).assert().isEqualTo(Range(1, 101))
        ranges.reserve(KEY, 100, 1).assert().isEqualTo(Range(101, 201))
        // The initial offset counts for a new key alone.
        ranges.reserve(KEY, 50, 1000).assert().isEqualTo(Range(201, 251))
    }

    @Test
    fun `two keys should reserve their ranges apart`() {
        ranges.reserve("a", 10, 1).assert().isEqualTo(Range(1, 11))
        ranges.reserve("b", 10, 5).assert().isEqualTo(Range(5, 15))
        ranges.reserve("a", 10, 1).assert().isEqualTo(Range(11, 21))
    }

    @Test
    fun `a size that is not positive should be refused`() {
        assertThrows<IllegalArgumentException> { ranges.reserve(KEY, 0, 1) }
        assertThrows<IllegalArgumentException> { ranges.reserve(KEY, -1, 1) }
        // Nothing was reserved: the key starts at its initial offset still.
        ranges.reserve(KEY, 10, 1).assert().isEqualTo(Range(1, 11))
    }

    @Test
    fun `a range past the largest value should exhaust its key, which keeps its last end`() {
        ranges.reserve(KEY, 10, Long.MAX_VALUE - 15).assert().isEqualTo(Range(Long.MAX_VALUE - 15, Long.MAX_VALUE - 5))
        assertThrows<RangeExhaustedException> { ranges.reserve(KEY, 10, 1) }.key().assert().isEqualTo(KEY)
        // The refused reserve changed nothing: a range that still fits ends at the largest value.
        ranges.reserve(KEY, 5, 1).assert().isEqualTo(Range(Long.MAX_VALUE - 5, Long.MAX_VALUE))
    }

    @Test
    fun `the store should lease no machine slots`() {
        assertThrows<UnsupportedOperationException> { store.slots() }
    }

    private companion object {
        const val KEY = "eshop-ordering-service/eshop-ordering.order"
    }
}
