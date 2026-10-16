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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/** [Kernel.stateAt]: the state at a version, from a snapshot and the tail up to it, else from the log. */
class KernelStateAtTest {
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var streamsRead = 0

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }
    }

    /** A cache that counts what is asked of it and keeps nothing. */
    private class CountingCache : StateCache {
        var calls = 0

        override fun take(ref: AggregateRef): CachedState? {
            calls++
            return null
        }

        override fun put(ref: AggregateRef, state: CachedState) {
            calls++
        }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private val cache = CountingCache()
    private val kernel = Kernel(log, codec, states = cache)
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val owned = Scope("alice", "space")
    private var sequence = 0

    private suspend fun send(body: Any, scope: Scope = Scope.NONE): CommandOutcome.Committed =
        kernel.execute(cartModel, Command("c-${++sequence}", "r-$sequence", ref, body, scope = scope))
            as CommandOutcome.Committed

    /** Commits four streams; returns the snapshot of version 2. */
    private suspend fun history(): SnapshotRecord {
        send(CreateCart("ahoo"), owned)
        val second = send(AddItem("apple", 1))
        send(AddItem("pear", 2))
        send(AddItem("apple", 3))
        cache.calls = 0
        log.streamsRead = 0
        return codec.snapshotOf(cartModel, ref, second.state as CartState, 2, owned, 0, 0)
    }

    private fun CachedState?.items(): Map<String, Int> = (this!!.state as CartState).items

    @Test
    fun `without a snapshot the log is replayed up to the version`(): Unit = runBlocking {
        history()
        val state = kernel.stateAt(cartModel, ref, 3)
        state.items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        state!!.version.assert().isEqualTo(3)
        state.scope.assert().isEqualTo(owned)
        log.streamsRead.assert().isEqualTo(3)
        cache.calls.assert().isEqualTo(0)
    }

    @Test
    fun `a fitting snapshot is evolved with the tail up to the version only`(): Unit = runBlocking {
        val snapshot = history()
        kernel.stateAt(cartModel, ref, 3, snapshot).items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        // The stream at the snapshot's version, then version 3; version 4 is not read.
        log.streamsRead.assert().isEqualTo(2)
        kernel.stateAt(cartModel, ref, 2, snapshot).items().assert().isEqualTo(mapOf("apple" to 1))
    }

    @Test
    fun `a snapshot past the version or not fitting is passed over`(): Unit = runBlocking {
        val snapshot = history()
        kernel.stateAt(cartModel, ref, 1, snapshot).items().assert().isEmpty()
        kernel.stateAt(cartModel, ref, 3, snapshot.copyWith(schemaFingerprint = "another"))
            .items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        kernel.stateAt(cartModel, ref, 3, snapshot.copyWith(ref = cartModel.type.ref("tenant", "other")))
            .items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
    }

    @Test
    fun `a version the log does not hold has no state`(): Unit = runBlocking {
        val snapshot = history()
        kernel.stateAt(cartModel, ref, 5).assert().isNull()
        kernel.stateAt(cartModel, ref, 5, snapshot).assert().isNull()
    }

    @Test
    fun `a base state is evolved with the streams after it only`(): Unit = runBlocking {
        history()
        val second = kernel.stateAt(cartModel, ref, 2)!!
        log.streamsRead = 0
        val base = CachedState(codec.decodeState(cartModel, codec.stateText(second.state)), 2, owned)
        val fourth = kernel.stateAt(cartModel, ref, 4, base)
        fourth.items().assert().isEqualTo(mapOf("apple" to 4, "pear" to 2))
        fourth!!.version.assert().isEqualTo(4)
        fourth.scope.assert().isEqualTo(owned)
        // Versions 3 and 4 only: the log is not replayed from version 1.
        log.streamsRead.assert().isEqualTo(2)
        cache.calls.assert().isEqualTo(0)
        // A base at the version is the state; a version past the log has none.
        kernel.stateAt(cartModel, ref, 2, second).assert().isSameAs(second)
        kernel.stateAt(cartModel, ref, 5, second).assert().isNull()
        runCatching { kernel.stateAt(cartModel, ref, 1, second) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { kernel.stateAt(cartModel, AggregateType("example", "order").ref("tenant", "o-1"), 2, second) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a state view gives the same snapshot as its state`() {
        val state = CartState("cart-1").apply { owner = "ahoo" }
        codec.snapshotOf(cartModel, ref, codec.stateText(state), 3, owned, 1, 2)
            .assert().isEqualTo(codec.snapshotOf(cartModel, ref, state, 3, owned, 1, 2))
    }
}
