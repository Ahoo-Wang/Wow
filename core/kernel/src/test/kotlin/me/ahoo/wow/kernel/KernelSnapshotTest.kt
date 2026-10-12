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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** On a cache miss the kernel loads a fitting snapshot and only the log's tail after it, else replays the log. */
class KernelSnapshotTest {
    /** Counts the streams read from the log. */
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var streamsRead = 0

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private val snapshots = InMemorySnapshotStore()
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    /** No state cache, so every command loads. */
    private val kernel = Kernel(log, codec, snapshots = snapshots)
    private val replaying = Kernel(log, codec)

    private val owned = Scope("alice", "space")

    private suspend fun send(kernel: Kernel, body: Any, scope: Scope = Scope.NONE): CommandOutcome =
        kernel.execute(cartModel, Command("c-${++sequence}", "r-$sequence", ref, body, scope = scope))

    /** Commits five streams and snapshots the state at version 3. */
    private suspend fun history() {
        send(replaying, CreateCart("ahoo"), owned)
        send(replaying, AddItem("apple", 1))
        val third = send(replaying, AddItem("pear", 2)) as CommandOutcome.Committed
        send(replaying, AddItem("apple", 3))
        send(replaying, AddItem("pear", 4))
        snapshots.save(
            codec.snapshotOf(
                cartModel,
                ref,
                third.state as CartState,
                3,
                third.stream.scope,
                firstEventTime = 0,
                lastEventTime = 0,
            ),
        )
    }

    private fun CommandOutcome.cart(): CartState = (this as CommandOutcome.Committed).state as CartState

    @Test
    fun `a snapshot and the tail give the state a full replay gives`() = runTest {
        history()
        log.streamsRead = 0
        val fromSnapshot = send(kernel, AddItem("apple", 10))
        // The stream at the snapshot's version proves the log holds it, then versions 4 and 5 are the tail.
        log.streamsRead.assert().isEqualTo(3)
        (fromSnapshot as CommandOutcome.Committed).stream.version.assert().isEqualTo(6)

        log.streamsRead = 0
        val replayed = send(replaying, AddItem("apple", 10)).cart()
        log.streamsRead.assert().isEqualTo(6)
        fromSnapshot.cart().items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
        replayed.items.assert().isEqualTo(mapOf("apple" to 24, "pear" to 6))
        fromSnapshot.cart().owner.assert().isEqualTo("ahoo")
    }

    @Test
    fun `the aggregate's scope comes from the creating command and is carried forward`(): Unit = runTest {
        history()
        // A later command's caller scope, here without the space, does not move the aggregate's.
        (send(replaying, AddItem("apple", 1), Scope("alice", null)) as CommandOutcome.Committed).stream.scope
            .assert().isEqualTo(owned)
        log.read(ref).toList().map { it.scope }.distinct().assert().containsExactly(owned)
    }

    @Test
    fun `a snapshot gives the scope, and a full replay the last stream's`(): Unit = runTest {
        history()
        (send(replaying, AddItem("apple", 1)) as CommandOutcome.Committed).stream.scope.assert().isEqualTo(owned)
        // A snapshot whose scope differs from the log's shows that the kernel took the scope from it.
        val marked = Scope("from-snapshot", null)
        snapshots.save(snapshots.load(ref)!!.copy(version = 6, scope = marked))
        (send(kernel, AddItem("apple", 1)) as CommandOutcome.Committed).stream.scope.assert().isEqualTo(marked)
    }

    @Test
    fun `a snapshot at the log's head reads no event beyond it`() = runTest {
        send(replaying, CreateCart("ahoo"))
        val head = send(replaying, AddItem("apple", 1)) as CommandOutcome.Committed
        snapshots.save(codec.snapshotOf(cartModel, ref, head.state as CartState, 2, head.stream.scope, 0, 0))
        log.streamsRead = 0
        send(kernel, AddItem("apple", 1)).cart().items.assert().isEqualTo(mapOf("apple" to 2))
        log.streamsRead.assert().isEqualTo(1)
    }

    @Test
    fun `a snapshot of another state schema is ignored`() = runTest {
        history()
        val stale = snapshots.load(ref)!!.copy(version = 4, schemaFingerprint = "another-schema", state = "{}")
        snapshots.save(stale)
        log.streamsRead = 0
        send(kernel, AddItem("apple", 10)).cart().items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
        log.streamsRead.assert().isEqualTo(5)
    }

    @Test
    fun `a snapshot that does not decode is ignored`() = runTest {
        history()
        snapshots.save(snapshots.load(ref)!!.copy(version = 4, state = """{"items": "not a map"}"""))
        log.streamsRead = 0
        send(kernel, AddItem("apple", 10)).cart().items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
        log.streamsRead.assert().isEqualTo(5)
    }

    @Test
    fun `a snapshot newer than the log is ignored`() = runTest {
        history()
        snapshots.save(snapshots.load(ref)!!.copy(version = 9))
        val outcome = send(kernel, AddItem("apple", 10)) as CommandOutcome.Committed
        outcome.stream.version.assert().isEqualTo(6)
        (outcome.state as CartState).items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
    }

    /** A store whose load always throws [failure]. */
    private class FailingStore(private val failure: Exception) : SnapshotStore {
        override suspend fun save(snapshot: SnapshotRecord): Boolean = false

        override suspend fun load(ref: AggregateRef): SnapshotRecord = throw failure
    }

    @Test
    fun `a failing snapshot store costs a full replay, not the command`() = runTest {
        history()
        log.streamsRead = 0
        val failing = Kernel(log, codec, snapshots = FailingStore(IllegalStateException("store down")))
        val outcome = failing.execute(cartModel, Command("c-down", "r-down", ref, AddItem("apple", 10)))
        outcome.cart().items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
        log.streamsRead.assert().isEqualTo(5)
    }

    @Test
    fun `cancellation while loading a snapshot is not swallowed`() = runTest {
        history()
        val cancelling = Kernel(log, codec, snapshots = FailingStore(CancellationException("cancelled")))
        assertThrows<CancellationException> {
            cancelling.execute(cartModel, Command("c-cancel", "r-cancel", ref, AddItem("apple", 10)))
        }
    }

    @Test
    fun `a mutable state round-trips through its snapshot`() {
        val state = CartState("cart-1").apply {
            owner = "ahoo"
            items["apple"] = 2
        }
        val decoded = codec.decodeState(cartModel, codec.stateText(state))
        decoded.owner.assert().isEqualTo("ahoo")
        decoded.items.assert().isEqualTo(mapOf("apple" to 2))
    }

    class Computed {
        var total = 0
        val doubled: Int get() = total * 2
    }

    @Test
    fun `a computed property does not stop a state from decoding`() {
        val model = aggregate<Computed>("test", "computed") { initial { Computed() } }
        codec.decodeState(model, codec.stateText(Computed().apply { total = 2 })).total.assert().isEqualTo(2)
    }

    @Test
    fun `a snapshot whose state would lose data is ignored`() = runTest {
        // Without getters as setters, Jackson leaves the cart's items empty instead of failing.
        val lossy = EventCodec(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        history()
        val snapshot = snapshots.load(ref)!!
        assertThrows<IllegalArgumentException> { lossy.decodeState(cartModel, snapshot.state) }
        log.streamsRead = 0
        val outcome = Kernel(log, lossy, snapshots = snapshots).execute(
            cartModel,
            Command("c-lossy", "r-lossy", ref, AddItem("apple", 10)),
        )
        outcome.cart().items.assert().isEqualTo(mapOf("apple" to 14, "pear" to 6))
        log.streamsRead.assert().isEqualTo(5)
    }

    @Test
    fun `the writer side records the version, scope, times and fingerprint`() {
        val state = CartState("cart-1").apply { owner = "ahoo" }
        val snapshot = codec.snapshotOf(cartModel, ref, state, 7, owned, firstEventTime = 1, lastEventTime = 5)
        snapshot.version.assert().isEqualTo(7)
        snapshot.scope.assert().isEqualTo(owned)
        snapshot.firstEventTime.assert().isEqualTo(1)
        snapshot.lastEventTime.assert().isEqualTo(5)
        snapshot.schemaFingerprint.assert().isEqualTo(codec.stateSchemaFingerprint(cartModel))
        codec.decodeState(cartModel, snapshot.state).owner.assert().isEqualTo("ahoo")
    }
}
