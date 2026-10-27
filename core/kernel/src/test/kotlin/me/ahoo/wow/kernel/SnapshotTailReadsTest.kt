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

package me.ahoo.wow.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.StoreFallbacks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * A cache miss reads the snapshot with the tail through its type's reader, and reads apart, counting the fallback,
 * when the reader fails (findings R5.B-9, R5.B-16).
 */
class SnapshotTailReadsTest {
    private val type = AggregateType("shop", "cart")
    private val ref = type.ref("tenant", "c-1")
    private val memory = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val fallbacks = ArrayList<Triple<String, String, String>>()
    private val observer = object : Observer {
        override fun storeFellBack(context: String, capability: String, reason: String) {
            fallbacks += Triple(context, capability, reason)
        }
    }
    private val served = SnapshotTail.of(null, AggregateTail.of(emptyList(), emptyList()))

    private fun reads(reader: SnapshotTailReader): SnapshotTailReads =
        SnapshotTailReads(memory, { if (it == type) reader else null }, observer)

    private suspend fun appendOne() {
        memory.append(
            EventStreamRecord.of(
                ref,
                1,
                "c",
                "r",
                "shop.cart.Add",
                0,
                listOf(EventRecord.of("shop.cart.Added", 1, "{}")),
                Scope.NONE
            ),
        )
    }

    @Test
    fun `a type without a reader is read apart, which is no fallback`() = runTest {
        appendOne()
        SnapshotTailReads(memory, { null }, observer).read(ref, snapshots, 2).tail.streams.map { it.version }
            .assert().containsExactly(1)
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a reader that serves is read through, with no fallback`() = runTest {
        reads { _, _ -> served }.read(ref, snapshots, 2).assert().isSameAs(served)
        fallbacks.assert().isEmpty()
    }

    @Test
    fun `a reader that fails is read apart and counted, never failing the load`() = runTest {
        appendOne()
        val reads = reads { _, _ -> throw IOException("The statement failed.") }
        repeat(2) { reads.read(ref, snapshots, 2).tail.streams.map { it.version }.assert().containsExactly(1) }
        fallbacks.assert().containsExactly(
            Triple("shop", StoreFallbacks.SNAPSHOT_TAIL_READ, StoreFallbacks.FAILED),
            Triple("shop", StoreFallbacks.SNAPSHOT_TAIL_READ, StoreFallbacks.FAILED),
        )
    }

    @Test
    fun `a cancelled read is cancelled, never read apart`() = runTest {
        val reads = reads { _, _ -> throw CancellationException("cancelled") }
        assertThrows<CancellationException> { reads.read(ref, snapshots, 2) }
        fallbacks.assert().isEmpty()
    }
}
