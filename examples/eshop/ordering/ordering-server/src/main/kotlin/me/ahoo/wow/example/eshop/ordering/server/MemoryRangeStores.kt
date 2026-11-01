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
import me.ahoo.cosid.lease.SlotLeaseStore
import me.ahoo.cosid.segment.Range
import me.ahoo.cosid.segment.RangeStore
import me.ahoo.cosid.store.Store
import me.ahoo.cosid.store.StoreConfig
import me.ahoo.cosid.store.StoreProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * The ranges of the order numbers in the service's memory, CosId's store type `memory`: the store of the memory
 * backend, where the node keeps its log in its JVM too, so a restart starts both afresh. The `postgres` and `mongo`
 * profiles keep the ranges in the service's own database instead (CosId's `jdbc` and `mongo` stores), where every
 * node of the service reserves its ranges from the same rows. It leases no machine slots: the order numbers' generator
 * is a segment, which needs none.
 */
class MemoryRangeStores : StoreProvider {
    override fun type(): String = TYPE

    override fun open(config: StoreConfig): Store = MemoryStore()

    private class MemoryStore : Store, RangeStore {
        /** The next free value of each key. */
        private val next = ConcurrentHashMap<String, Long>()

        override fun ranges(): RangeStore = this

        override fun slots(): SlotLeaseStore =
            throw UnsupportedOperationException("The $TYPE store leases no machine slots, only ranges.")

        override fun reserve(key: String, size: Long, initialOffset: Long): Range {
            require(size > 0) { "A range has a positive size: $size." }
            var start = 0L
            next.compute(key) { _, free ->
                start = free ?: initialOffset
                if (start > Long.MAX_VALUE - size) throw RangeExhaustedException(key)
                start + size
            }
            return Range(start, start + size)
        }

        override fun close() = Unit
    }

    companion object {
        /** The store type, `cosid.stores.<name>.type`. */
        const val TYPE: String = "memory"
    }
}
