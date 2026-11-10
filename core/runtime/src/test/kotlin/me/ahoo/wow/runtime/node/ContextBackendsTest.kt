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

package me.ahoo.wow.runtime.node

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.store.Store
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The checks a context's backends make, and the value semantics of the stores and readers they carry. */
class ContextBackendsTest {
    private val log = InMemoryEventLog(2)
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()

    private fun group(store: String, type: String) =
        LogGroupBackends(store, setOf(AggregateType("sales", type)), InMemoryEventLog(2), checkpoints, ledger)

    private fun backends(vararg groups: LogGroupBackends) =
        ContextBackends(log, checkpoints, InMemoryLeaseStore(), ledger, store = "main", groups = groups.toList())

    @Test
    fun `each log group is on a store of its own, not the context's default one`() {
        backends(group("other", "invoice")).groups.size.assert().isEqualTo(1)
        assertThrows<IllegalArgumentException> { backends(group("main", "invoice")) }
        assertThrows<IllegalArgumentException> { backends(group("other", "invoice"), group("other", "bill")) }
    }

    @Test
    fun `placed stores differ from another value and from stores placed otherwise`() {
        val a = mockk<Store>()
        val b = mockk<Store>()
        val placed = PlacedStores(setOf(a), setOf(b))
        placed.assert().isNotEqualTo(PlacedStores(setOf(a)))
        (placed as Any).assert().isNotEqualTo(setOf(a, b))
    }

    @Test
    fun `snapshot tail readers wrap the readers placed by type, and print a missing default`() {
        val order = AggregateType("shop", "order")
        val placed = SnapshotTailReader { _, _ -> error("unused") }
        val wrapped = SnapshotTailReader { _, _ -> error("unused") }
        val readers = SnapshotTailReaders(null, mapOf(order to placed))
        val types = mutableListOf<AggregateType?>()
        readers.map { type, _ -> wrapped.also { types += type } }.of(order).assert().isSameAs(wrapped)
        types.assert().containsExactly(order)
        readers.toString().assert().isEqualTo("SnapshotTailReaders(default=false, placed=[shop.order])")
    }
}
