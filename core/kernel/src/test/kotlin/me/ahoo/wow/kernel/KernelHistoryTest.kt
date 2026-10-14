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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant

/** [Kernel.stateAt] by time and [Kernel.trace]: historical state (decision D40). */
class KernelHistoryTest {
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var streamsRead = 0

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private var now = 0L
    private val kernel = Kernel(log, codec, clock = { now })
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val owned = Scope("alice", "space")
    private var sequence = 0

    private suspend fun send(body: Any, at: Long, scope: Scope = Scope.NONE): CommandOutcome.Committed {
        now = at
        return kernel.execute(cartModel, Command("c-${++sequence}", "r-$sequence", ref, body, scope = scope))
            as CommandOutcome.Committed
    }

    /** Versions 1 to 4 committed at 1000, 2000, 3000 and 4000 ms; returns the snapshot of version 2. */
    private suspend fun history(): SnapshotRecord {
        send(CreateCart("ahoo"), 1000, owned)
        val second = send(AddItem("apple", 1), 2000)
        send(AddItem("pear", 2), 3000)
        send(AddItem("apple", 3), 4000)
        log.streamsRead = 0
        return codec.snapshotOf(cartModel, ref, second.state as CartState, 2, owned, 1000, 2000)
    }

    private fun millis(value: Long): Instant = Instant.ofEpochMilli(value)

    private fun CachedState?.items(): Map<String, Int> = (this!!.state as CartState).items

    @Test
    fun `a time before the first commit has no state`(): Unit = runBlocking {
        val snapshot = history()
        kernel.stateAt(cartModel, ref, millis(999)).assert().isNull()
        kernel.stateAt(cartModel, ref, millis(999), snapshot).assert().isNull()
        kernel.stateAt(cartModel, ref, Instant.MIN).assert().isNull()
        kernel.stateAt(cartModel, cartModel.type.ref("tenant", "none"), millis(5000)).assert().isNull()
    }

    @Test
    fun `a commit belongs to the state from its commit time on`(): Unit = runBlocking {
        history()
        kernel.stateAt(cartModel, ref, millis(1000))!!.version.assert().isEqualTo(1)
        kernel.stateAt(cartModel, ref, millis(1999))!!.version.assert().isEqualTo(1)
        // Commit times are milliseconds: an instant within the commit's millisecond holds it.
        kernel.stateAt(cartModel, ref, millis(2000).plusNanos(999_999))!!.version.assert().isEqualTo(2)
        val third = kernel.stateAt(cartModel, ref, millis(3000))
        third!!.version.assert().isEqualTo(3)
        third.items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        third.scope.assert().isEqualTo(owned)
        kernel.stateAt(cartModel, ref, Instant.MAX)!!.version.assert().isEqualTo(4)
    }

    @Test
    fun `the log is read only up to the first commit after the time`(): Unit = runBlocking {
        history()
        kernel.stateAt(cartModel, ref, millis(2500))!!.version.assert().isEqualTo(2)
        // Versions 1 and 2, and version 3 that ends the read.
        log.streamsRead.assert().isEqualTo(3)
    }

    @Test
    fun `a snapshot committed by the time is evolved with the tail up to it`(): Unit = runBlocking {
        val snapshot = history()
        val state = kernel.stateAt(cartModel, ref, millis(3500), snapshot)
        state!!.version.assert().isEqualTo(3)
        state.items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        // The snapshot's own stream, version 3, and version 4 that ends the read: never versions 1.
        log.streamsRead.assert().isEqualTo(3)
        log.streamsRead = 0
        kernel.stateAt(cartModel, ref, millis(2000), snapshot)!!.version.assert().isEqualTo(2)
        log.streamsRead.assert().isEqualTo(2)
    }

    @Test
    fun `a snapshot after the time, not fitting or disagreeing with the log is passed over`(): Unit = runBlocking {
        val snapshot = history()
        kernel.stateAt(cartModel, ref, millis(1500), snapshot).items().assert().isEmpty()
        kernel.stateAt(cartModel, ref, millis(3000), snapshot.copy(schemaFingerprint = "another"))
            .items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        kernel.stateAt(cartModel, ref, millis(3000), snapshot.copy(ref = cartModel.type.ref("tenant", "other")))
            .items().assert().isEqualTo(mapOf("apple" to 1, "pear" to 2))
        // A snapshot claiming an earlier commit time than its stream has.
        kernel.stateAt(cartModel, ref, millis(1500), snapshot.copy(firstEventTime = 1000, lastEventTime = 1000))
            .items().assert().isEmpty()
    }

    @Test
    fun `the state after a deletion is the deleted one, until it is restored`(): Unit = runBlocking {
        send(CreateCart("ahoo"), 1000, owned)
        send(DeleteAggregate, 2000)
        send(RestoreAggregate, 3000)
        kernel.stateAt(cartModel, ref, millis(1500))!!.deleted.assert().isFalse()
        kernel.stateAt(cartModel, ref, millis(2500))!!.deleted.assert().isTrue()
        kernel.stateAt(cartModel, ref, millis(3500))!!.deleted.assert().isFalse()
    }

    @Test
    fun `a trace gives each commit with its events and the state after it`(): Unit = runBlocking {
        history()
        val trace = kernel.trace(cartModel, ref, 1, Int.MAX_VALUE, 10)
        trace.map { it.version }.assert().isEqualTo(listOf(1, 2, 3, 4))
        trace[0].events.assert().isEqualTo(listOf(CartCreated("ahoo")))
        trace[0].stream.commitTime.assert().isEqualTo(1000L)
        trace[0].scope.assert().isEqualTo(owned)
        // Each state is kept as it was after its commit, though the cart's state is mutable.
        trace.map { it.state["items"].properties().associate { (key, value) -> key to value.asInt() } }
            .assert().isEqualTo(
                listOf(
                    emptyMap(),
                    mapOf("apple" to 1),
                    mapOf("apple" to 1, "pear" to 2),
                    mapOf("apple" to 4, "pear" to 2),
                ),
            )
        trace[3].events.assert().isEqualTo(listOf(ItemAdded("apple", 3)))
    }

    @Test
    fun `a trace stops at its last version and at its limit`(): Unit = runBlocking {
        history()
        kernel.trace(cartModel, ref, 2, 3, 10).map { it.version }.assert().isEqualTo(listOf(2, 3))
        kernel.trace(cartModel, ref, 2, Int.MAX_VALUE, 2).map { it.version }.assert().isEqualTo(listOf(2, 3))
        kernel.trace(cartModel, ref, 4, Int.MAX_VALUE, Int.MAX_VALUE).map { it.version }.assert().isEqualTo(listOf(4))
        kernel.trace(cartModel, ref, 5, 9, 10).assert().isEmpty()
        kernel.trace(cartModel, ref, 9, 9, 10).assert().isEmpty()
        kernel.trace(cartModel, cartModel.type.ref("tenant", "none"), 1, 9, 10).assert().isEmpty()
    }

    @Test
    fun `a trace starts from a fitting snapshot before its first version`(): Unit = runBlocking {
        val snapshot = history()
        val trace = kernel.trace(cartModel, ref, 3, 4, 10, snapshot)
        trace.map { it.version }.assert().isEqualTo(listOf(3, 4))
        trace[0].state["items"]["pear"].asInt().assert().isEqualTo(2)
        // The snapshot's stream to prove it, then versions 3 and 4: version 1 is never read.
        log.streamsRead.assert().isEqualTo(3)
        log.streamsRead = 0
        // A snapshot at or past the first version does not help: the log is replayed up to it.
        kernel.trace(cartModel, ref, 2, 2, 10, snapshot)[0].state["items"]["apple"].asInt().assert().isEqualTo(1)
        log.streamsRead.assert().isEqualTo(2)
    }

    @Test
    fun `a trace carries the deleted mark, the tags and the scope after each commit`(): Unit = runBlocking {
        send(CreateCart("ahoo"), 1000, owned)
        send(ApplyTags(mapOf("color" to listOf("red"))), 2000)
        send(TransferOwner("bob"), 3000)
        send(DeleteAggregate, 4000)
        val trace = kernel.trace(cartModel, ref, 1, 4, 10)
        trace.map { it.tags }.assert().isEqualTo(
            listOf(
                emptyMap(),
                mapOf("color" to listOf("red")),
                mapOf("color" to listOf("red")),
                mapOf("color" to listOf("red"))
            ),
        )
        trace.map { it.scope.ownerId }.assert().isEqualTo(listOf("alice", "alice", "bob", "bob"))
        trace.map { it.deleted }.assert().isEqualTo(listOf(false, false, false, true))
        trace[3].events.assert().isEqualTo(listOf(AggregateDeleted))
    }

    @Test
    fun `a trace is asked for with a range and a limit`(): Unit = runBlocking {
        val other = aggregate<CartState>("example", "basket") { initial { CartState(it.id) } }
        listOf<suspend () -> Any?>(
            { kernel.trace(cartModel, ref, 0, 1, 1) },
            { kernel.trace(cartModel, ref, 2, 1, 1) },
            { kernel.trace(cartModel, ref, 1, 1, 0) },
            { kernel.trace(other, ref, 1, 1, 1) },
            { kernel.stateAt(other, ref, millis(0)) },
        ).forEach { call ->
            runCatching { call() }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    /** Revision 2 of an event that began as `{"amount": ...}` and then named its note. */
    data class Counted(val amount: BigDecimal, val note: String)

    class Counter(val id: String) {
        var total: BigDecimal = BigDecimal.ZERO
        val notes = mutableListOf<String>()
    }

    private val counterModel = aggregate<Counter>("example", "counter") {
        initial { Counter(it.id) }
        evolve<Counted>(revision = 2) {
            total += it.amount
            notes += it.note
        }
        upgrade<Counted>(from = 1) { payload -> (payload as ObjectNode).put("note", "upgraded") }
    }

    private suspend fun counted(counter: AggregateRef, version: Int, revision: Int, payload: String, at: Long) {
        log.append(
            EventStreamRecord(
                ref = counter,
                version = version,
                commandId = "c-$version",
                requestId = "r-$version",
                commandType = "example.counter.Count",
                commitTime = at,
                events = listOf(EventRecord("example.counter.Counted", revision, payload)),
                scope = Scope.NONE,
            ),
        )
    }

    @Test
    fun `history reads old revisions through their upgrades and keeps numbers exact`(): Unit = runBlocking {
        val counter = counterModel.type.ref("tenant", "counter-1")
        counted(counter, 1, 1, """{"amount":2.50}""", 1000)
        counted(counter, 2, 2, """{"amount":1.25,"note":"current"}""", 2000)

        val first = kernel.stateAt(counterModel, counter, millis(1000))!!.state as Counter
        first.total.assert().isEqualTo(BigDecimal("2.50"))
        first.notes.assert().isEqualTo(listOf("upgraded"))

        val trace = kernel.trace(counterModel, counter, 1, 2, 10)
        trace[0].events.assert().isEqualTo(listOf(Counted(BigDecimal("2.50"), "upgraded")))
        trace[0].state["total"].decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        trace[0].state["total"].decimalValue().scale().assert().isEqualTo(2)
        trace[1].state["total"].decimalValue().assert().isEqualTo(BigDecimal("3.75"))
        trace[1].state["notes"].toString().assert().isEqualTo("""["upgraded","current"]""")
    }
}
