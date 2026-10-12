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

package me.ahoo.wow.http.read

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.Cart
import me.ahoo.wow.http.cartModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.admission.ModelRegistry
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** [KernelStateReads] and [StateReads.NONE] for what they do not serve. */
class KernelStateReadsTest {
    private val snapshots = InMemorySnapshotStore()
    private val registry = ModelRegistry(listOf(cartModel))
    private val reads = KernelStateReads(
        registry,
        mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(InMemoryEventLog(partitions = 1)), snapshots)),
    )
    private val cart = AggregateType(CONTEXT, "cart").ref("t", "c-1")

    @Test
    fun `an aggregate of a context or a type not served is not found`(): Unit = runBlocking {
        val otherContext = AggregateType("billing", "cart").ref("t", "c-1")
        val otherType = AggregateType(CONTEXT, "basket").ref("t", "c-1")
        listOf(otherContext, otherType).forEach { ref ->
            reads.latest(ref).assert().isNull()
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
        StateReads.NONE.at(cart, 1).assert().isNull()
        StateReads.NONE.at(cart, Instant.MAX).assert().isNull()
        StateReads.NONE.trace(cart, 1, 1, 1).assert().isEmpty()
    }

    @Test
    fun `the latest state keeps a decimal exact (D122)`(): Unit = runBlocking {
        val snapshot = EventCodec().snapshotOf(cartModel, cart, Cart(), 1, Scope.NONE, 1L, 1L)
        snapshots.save(snapshot.copy(state = """{"items":1,"price":2.50}"""))
        val price = reads.latest(cart)!!.state["price"]
        price.decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        price.decimalValue().scale().assert().isEqualTo(2)
    }
}
