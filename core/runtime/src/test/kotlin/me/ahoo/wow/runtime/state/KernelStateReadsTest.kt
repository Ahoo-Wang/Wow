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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** [KernelStateReads] and [StateReads.NONE] for what they do not serve. */
class KernelStateReadsTest {
    class Cart {
        var items = 0
    }

    data class ItemAdded(val quantity: Int)

    private val cartModel = aggregate<Cart>(CONTEXT, "cart") {
        initial { Cart() }
        evolve<ItemAdded> { items += it.quantity }
    }
    private val log = InMemoryEventLog(partitions = 1)
    private val snapshots = InMemorySnapshotStore()
    private val registry = ModelRegistry(listOf(cartModel))
    private val reads = KernelStateReads(
        registry,
        mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(log), snapshots)),
    )
    private val cart = AggregateType(CONTEXT, "cart").ref("t", "c-1")

    @Test
    fun `an aggregate of a context or a type not served is not found`(): Unit = runBlocking {
        val otherContext = AggregateType("billing", "cart").ref("t", "c-1")
        val otherType = AggregateType(CONTEXT, "basket").ref("t", "c-1")
        listOf(otherContext, otherType).forEach { ref ->
            reads.latest(ref).assert().isNull()
            reads.committed(ref).assert().isNull()
            reads.at(ref, 1).assert().isNull()
            reads.at(ref, Instant.MAX).assert().isNull()
            reads.trace(ref, 1, 1, 1).assert().isEmpty()
        }
        // The context is served, the model is not: even with a snapshot of it.
        val partial = KernelStateReads(
            ModelRegistry(emptyList()),
            mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(InMemoryEventLog(partitions = 1)), snapshots)),
        )
        snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart(), 1, Scope.NONE, 1L, 1L))
        partial.latest(cart).assert().isNull()
        partial.committed(cart).assert().isNull()
        partial.at(cart, 1).assert().isNull()
        partial.at(cart, Instant.MAX).assert().isNull()
        partial.trace(cart, 1, 1, 1).assert().isEmpty()
        reads.latest(cart)!!.version.assert().isEqualTo(1)
    }

    @Test
    fun `a state at a version before 1 is not asked for`(): Unit = runBlocking {
        runCatching { reads.at(cart, 0) }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        reads.at(cart, 1).assert().isNull()
    }

    @Test
    fun `NONE reads nothing`(): Unit = runBlocking {
        StateReads.NONE.latest(cart).assert().isNull()
        StateReads.NONE.committed(cart).assert().isNull()
        StateReads.NONE.at(cart, 1).assert().isNull()
        StateReads.NONE.at(cart, Instant.MAX).assert().isNull()
        StateReads.NONE.trace(cart, 1, 1, 1).assert().isEmpty()
    }

    @Test
    fun `the latest state keeps a decimal exact (D122)`(): Unit = runBlocking {
        val snapshot = EventCodec().snapshotOf(cartModel, cart, Cart(), 1, Scope.NONE, 1L, 1L)
        snapshots.save(snapshot.copyWith(state = """{"items":1,"price":2.50}"""))
        val price = reads.latest(cart)!!.state["price"]
        price.decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        price.decimalValue().scale().assert().isEqualTo(2)
    }

    @Test
    fun `the committed state is the snapshot, else the log before the first snapshot`(): Unit = runBlocking {
        reads.committed(cart).assert().isNull()
        (1..2).forEach { version ->
            log.append(
                EventStreamRecord(
                    ref = cart,
                    version = version,
                    commandId = "cmd-$version",
                    requestId = "req-$version",
                    commandType = "shop.cart.AddItem",
                    commitTime = 1000L * version,
                    events = listOf(EventCodec().encode(cartModel, ItemAdded(version))),
                    scope = Scope("alice", null),
                ),
            )
        }
        // No snapshot yet: the latest state is not found, the committed one is the log replayed.
        reads.latest(cart).assert().isNull()
        reads.committed(cart)!!.let { committed ->
            committed.version.assert().isEqualTo(2)
            committed.scope.assert().isEqualTo(Scope("alice", null))
            committed.state["items"].asInt().assert().isEqualTo(3)
        }
        // Once a snapshot is written, it is the committed state.
        snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart().apply { items = 1 }, 1, Scope.NONE, 1L, 1L))
        reads.committed(cart)!!.version.assert().isEqualTo(1)
        reads.committed(cart)!!.state["items"].asInt().assert().isEqualTo(1)
    }

    private companion object {
        const val CONTEXT = "shop"
    }
}
