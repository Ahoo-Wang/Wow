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

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ReplayLimitExceededException
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun `the latest state is the snapshot, else the log before the first snapshot (D246)`(): Unit = runBlocking {
        reads.latest(cart).assert().isNull()
        reads.committed(cart).assert().isNull()
        addItems(2)
        // No snapshot yet: the latest state, and the committed one, are the log replayed.
        listOf(reads.latest(cart)!!, reads.committed(cart)!!).forEach { read ->
            read.version.assert().isEqualTo(2)
            read.scope.assert().isEqualTo(Scope("alice", null))
            read.state["items"].asInt().assert().isEqualTo(3)
        }
        // Once a snapshot is written, it is the state read, even behind the log's head (D69).
        snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart().apply { items = 1 }, 1, Scope.NONE, 1L, 1L))
        listOf(reads.latest(cart)!!, reads.committed(cart)!!).forEach { read ->
            read.version.assert().isEqualTo(1)
            read.state["items"].asInt().assert().isEqualTo(1)
        }
    }

    private fun waiting(wait: Duration) = KernelStateReads(
        registry,
        mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(log), snapshots)),
        minVersionWait = wait,
    )

    @Test
    fun `a minimum version is read from the snapshot, else the snapshot caught up with the log (Q-13)`(): Unit =
        runBlocking {
            addItems(3)
            snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart().apply { items = 3 }, 2, Scope.NONE, 1L, 2L))
            reads.latest(cart)!!.version.assert().isEqualTo(2)
            reads.latest(cart, 2)!!.version.assert().isEqualTo(2)
            // The snapshot lags: the log's tail is replayed from it.
            reads.latest(cart, 3).let { read ->
                read!!.version.assert().isEqualTo(3)
                read.state["items"].asInt().assert().isEqualTo(6)
            }
            runCatching { reads.latest(cart, 0) }.exceptionOrNull().assert()
                .isInstanceOf(IllegalArgumentException::class.java)
            reads.latest(AggregateType("billing", "cart").ref("t", "c-1"), 1).assert().isNull()
        }

    @Test
    fun `a minimum version not committed yet is waited for, and past the wait it fails with the current version`():
        Unit = runBlocking {
        addItems(1)
        val failure = runCatching { waiting(30.milliseconds).latest(cart, 2) }.exceptionOrNull()
        (failure as MinVersionNotReachedException).version.assert().isEqualTo(1)
        failure.minVersion.assert().isEqualTo(2)
        failure.ref.assert().isEqualTo(cart)
        (
            runCatching { waiting(Duration.ZERO).latest(cart.type.ref("t", "none"), 1) }.exceptionOrNull()
                as MinVersionNotReachedException
            ).version.assert().isEqualTo(0)
        // Committed while the read waits: it is read.
        coroutineScope {
            val read = async { waiting(5.seconds).latest(cart, 2) }
            delay(100)
            log.append(
                EventStreamRecord(
                    ref = cart,
                    version = 2,
                    commandId = "cmd-late",
                    requestId = "req-late",
                    commandType = "shop.cart.AddItem",
                    commitTime = 2000L,
                    events = listOf(EventCodec().encode(cartModel, ItemAdded(5))),
                    scope = Scope("alice", null),
                ),
            )
            read.await()!!.state["items"].asInt().assert().isEqualTo(6)
        }
        runCatching { KernelStateReads(registry, emptyMap(), minVersionWait = (-1).seconds) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        (runCatching { StateReads.NONE.latest(cart, 1) }.exceptionOrNull() as MinVersionNotReachedException)
            .version.assert().isEqualTo(0)
    }

    @Test
    fun `every replay but the committed state's runs under the gate`(): Unit = runBlocking {
        val gated = mutableListOf<AggregateRef>()
        val refusing = KernelStateReads(
            registry,
            mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(log), snapshots)),
            object : ReplayGate {
                override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
                    gated += ref
                    throw IllegalStateException("busy")
                }
            },
        )
        addItems(1)
        // The latest state before the first snapshot, a state at a version or a time, and a trace (review R4-Q, Q-1).
        listOf<suspend () -> Any?>(
            { refusing.latest(cart) },
            { refusing.at(cart, 1) },
            { refusing.at(cart, Instant.MAX) },
            { refusing.trace(cart, 1, 1, 1) },
        ).forEach { read ->
            runCatching { read() }.exceptionOrNull()!!.message.assert().isEqualTo("busy")
        }
        gated.assert().containsExactly(cart, cart, cart, cart)
        // The events model's committed state is read outside the gate.
        refusing.committed(cart)!!.version.assert().isEqualTo(1)
        // A snapshot is read without a replay.
        snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart().apply { items = 1 }, 1, Scope.NONE, 1L, 1L))
        refusing.latest(cart)!!.version.assert().isEqualTo(1)
        gated.assert().hasSize(4)
        ReplayGate.OPEN.toString().assert().isEqualTo("ReplayGate.OPEN")
    }

    @Test
    fun `a replay longer than the bound is refused, the committed state unbounded`(): Unit = runBlocking {
        val bounded = KernelStateReads(
            registry,
            mapOf(CONTEXT to KernelStateReads.ContextReads(Kernel(log), snapshots)),
            maxReplay = 2,
        )
        addItems(4)
        listOf<suspend () -> Any?>(
            { bounded.latest(cart) },
            { bounded.at(cart, 4) },
            { bounded.at(cart, Instant.MAX) },
            { bounded.trace(cart, 4, 4, 1) },
        ).forEach { read ->
            val refused = runCatching { read() }.exceptionOrNull() as StateReplayLimitException
            refused.ref.assert().isEqualTo(cart)
            refused.limit.assert().isEqualTo(2)
            refused.cause.assert().isInstanceOf(ReplayLimitExceededException::class.java)
        }
        bounded.committed(cart)!!.version.assert().isEqualTo(4)
        bounded.at(cart, 2)!!.version.assert().isEqualTo(2)
        bounded.trace(cart, 1, 4, 4).assert().hasSize(4)
        // Counted from the snapshot when it fits.
        snapshots.save(EventCodec().snapshotOf(cartModel, cart, Cart().apply { items = 3 }, 2, Scope.NONE, 1L, 2000L))
        bounded.at(cart, 4)!!.state["items"].asInt().assert().isEqualTo(10)
        runCatching { KernelStateReads(registry, emptyMap(), maxReplay = 0) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        KernelStateReads.DEFAULT_MAX_REPLAY.assert().isEqualTo(10_000)
    }

    private suspend fun addItems(count: Int) {
        (1..count).forEach { version ->
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
    }

    private companion object {
        const val CONTEXT = "shop"
    }
}
