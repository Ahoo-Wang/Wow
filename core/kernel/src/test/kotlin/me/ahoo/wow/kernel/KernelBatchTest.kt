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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** A batch of one aggregate's queued commands, decided in turn and committed with one append (roadmap §6 item 10). */
class KernelBatchTest {
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0
    private val log = RecordingLog()
    private val states = MapCache()
    private val kernel = Kernel(log, states = states, options = KernelOptions(recentRequests = 256))

    private fun queued(body: Any, requestId: String = "request-${++sequence}") =
        QueuedCommand(Command("command-${++sequence}", requestId, ref, body))

    private suspend fun batch(vararg queued: QueuedCommand): List<BatchOutcome> =
        kernel.executeBatch(cartModel, queued.toList())

    private suspend fun create() {
        kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
        log.groups.clear()
    }

    private fun BatchOutcome.outcome(): CommandOutcome = (this as BatchOutcome.Done).outcome

    private fun BatchOutcome.version(): Int = (outcome() as CommandOutcome.Committed).stream.version

    @Test
    fun `an aggregate's queued commands commit with one append, in order`() = runTest {
        create()
        val outcomes = batch(queued(AddItem("a", 1)), queued(AddItem("a", 2)), queued(AddItem("b", 1)))
        outcomes.map { it.version() }.assert().containsExactly(2, 3, 4)
        log.groups.single().map { it.version }.assert().containsExactly(2, 3, 4)
        val cached = checkNotNull(states.states[ref])
        cached.version.assert().isEqualTo(4)
        (cached.state as CartState).items.assert().isEqualTo(mapOf("a" to 3, "b" to 1))
        log.read(ref).toList().map { it.version }.assert().containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `a batch ends before a command that appends nothing, which runs next alone`() = runTest {
        create()
        val outcomes = batch(queued(AddItem("a", 1)), queued(AddItem("b", 1)), queued(AddItem("c", 1)))
        outcomes.take(2).map { it.version() }.assert().containsExactly(2, 3)
        outcomes[2].assert().isEqualTo(BatchOutcome.Again)
        // The cart is full: run again, the command is rejected on its own, as execute rejects it.
        val again = batch(queued(AddItem("c", 1)), queued(AddItem("a", 1)))
        (again[0].outcome() as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("cart.full")
        again[1].assert().isEqualTo(BatchOutcome.Again)
    }

    @Test
    fun `a command with I-O to decide ends the batch, or runs alone when it is the first`() = runTest {
        create()
        val ended = batch(queued(AddItem("a", 1)), queued(CheckStock("a")))
        ended[0].version().assert().isEqualTo(2)
        ended[1].assert().isEqualTo(BatchOutcome.Again)
        val first = batch(queued(CheckStock("a")), queued(AddItem("a", 1)))
        first[0].version().assert().isEqualTo(3)
        first[1].assert().isEqualTo(BatchOutcome.Again)
    }

    @Test
    fun `a request repeated in the batch, or committed before, ends the batch`() = runTest {
        create()
        val repeated = batch(queued(AddItem("a", 1), "same"), queued(AddItem("a", 1), "same"))
        repeated[0].version().assert().isEqualTo(2)
        repeated[1].assert().isEqualTo(BatchOutcome.Again)
        // Run again, it is the duplicate the aggregate's recent requests answer.
        val duplicate = batch(queued(AddItem("a", 1), "same"), queued(AddItem("b", 1)))
        (duplicate[0].outcome() as CommandOutcome.AlreadyCommitted).stream.version.assert().isEqualTo(2)
        duplicate[1].assert().isEqualTo(BatchOutcome.Again)
    }

    @Test
    fun `without recent requests the first command runs alone`() = runTest {
        val kernel = Kernel(log, states = states, options = KernelOptions(recentRequests = 0))
        kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
        val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))
        outcomes[0].version().assert().isEqualTo(2)
        outcomes[1].assert().isEqualTo(BatchOutcome.Again)
    }

    @Test
    fun `on a stale state the first stream conflicts and is decided again alone, the others run again`() = runTest {
        create()
        batch(queued(AddItem("a", 1)))
        // Another node appends version 3 while this node's cache holds version 2.
        log.append(
            EventStreamRecord(
                ref = ref,
                version = 3,
                commandId = "other",
                requestId = "other",
                commandType = "example.cart.AddItem",
                commitTime = 1L,
                events = listOf(EventRecord("example.cart.ItemAdded", 1, """{"productId":"b","quantity":1}""")),
                scope = Scope.NONE,
            ),
        )
        log.groups.clear()
        val outcomes = batch(queued(AddItem("b", 1)), queued(AddItem("b", 1)))
        outcomes[0].version().assert().isEqualTo(4)
        outcomes[1].assert().isEqualTo(BatchOutcome.Again)
        log.groups.first().map { it.version }.assert().containsExactly(3, 4)
        (checkNotNull(states.states[ref]).state as CartState).items.assert().isEqualTo(mapOf("a" to 1, "b" to 2))
    }

    @Test
    fun `a command that fails deciding ends the batch, drops the state, and fails alone`() = runTest {
        val tally = tallyModel()
        kernel.execute(tally, Command("first", "first", tallyRef, TallyAdd(1)))
        val ended = kernel.executeBatch(
            tally,
            listOf(tallied(TallyAdd(2)), tallied(TallyBoom("x")), tallied(TallyAdd(3)))
        )
        ended[0].version().assert().isEqualTo(2)
        ended.drop(1).forEach { it.assert().isEqualTo(BatchOutcome.Again) }
        // It may have evolved the state part way: dropped, and loaded again.
        states.states[tallyRef].assert().isNull()
        val alone = kernel.executeBatch(tally, listOf(tallied(TallyBoom("y")), tallied(TallyAdd(3))))
        (alone[0] as BatchOutcome.Failed).failure.message.assert().isEqualTo("boom y")
        alone[1].assert().isEqualTo(BatchOutcome.Again)
        states.states[tallyRef].assert().isNull()
    }

    @Test
    fun `a command of a type the aggregate does not decide ends the batch`() = runTest {
        create()
        val outcomes = batch(queued(AddItem("a", 1)), queued(TallyAdd(1)))
        outcomes[0].version().assert().isEqualTo(2)
        outcomes[1].assert().isEqualTo(BatchOutcome.Again)
    }

    @Test
    fun `an append that throws fails every command of the batch and drops the state`() = runTest {
        create()
        log.failure = IOException("unreachable")
        val outcomes = batch(queued(AddItem("a", 1)), queued(AddItem("b", 1)))
        outcomes.forEach { (it as BatchOutcome.Failed).failure.message.assert().isEqualTo("unreachable") }
        states.states[ref].assert().isNull()
        log.failure = null
        batch(queued(AddItem("a", 1)))[0].version().assert().isEqualTo(2)
    }

    @Test
    fun `a command sent for a schedule ends the batch, and runs next alone`() = runTest {
        create()
        val due = ScheduleContext("$ref#remind@1", "remind", 1, 0L).headers()
        val scheduled = QueuedCommand(Command("scheduled", "$ref#remind@1#1", ref, AddItem("b", 1), headers = due))
        val ended = batch(queued(AddItem("a", 1)), scheduled)
        ended[0].version().assert().isEqualTo(2)
        ended[1].assert().isEqualTo(BatchOutcome.Again)
        log.groups.single().map { it.version }.assert().containsExactly(2)
        // First in the lane, it runs alone, as execute decides a scheduled command.
        val alone = batch(scheduled, queued(AddItem("c", 1)))
        alone[0].version().assert().isEqualTo(3)
        alone[1].assert().isEqualTo(BatchOutcome.Again)
        log.read(ref).toList().last().headers.assert().containsAllEntriesOf(due)
    }

    @Test
    fun `a first command refused on an aggregate not yet created is answered alone, and nothing is appended`() =
        runTest {
            val outcomes = batch(queued(AddItem("a", 1)), queued(AddItem("b", 1)))
            (outcomes[0].outcome() as CommandOutcome.Rejected).rejection.code
                .assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
            outcomes[1].assert().isEqualTo(BatchOutcome.Again)
            log.groups.assert().isEmpty()
            log.read(ref).toList().assert().isEmpty()
        }

    @Test
    fun `a request another aggregate of the type committed is answered as that duplicate, and nothing is appended`() =
        runTest {
            create()
            val other = cartModel.type.ref("tenant", "cart-2")
            kernel.execute(cartModel, Command("other-create", "other-create", other, CreateCart("bob")))
            val committed = kernel.execute(cartModel, Command("other-add", "shared", other, AddItem("x", 1)))
                as CommandOutcome.Committed
            log.groups.clear()
            // Not among cart-1's recent requests, so it joins the batch; the append meets the request key.
            val outcomes = batch(queued(AddItem("a", 1), "shared"), queued(AddItem("b", 1)))
            val duplicate = outcomes[0].outcome() as CommandOutcome.AlreadyCommitted
            duplicate.stream.ref.assert().isEqualTo(other)
            duplicate.stream.version.assert().isEqualTo(committed.stream.version)
            outcomes[1].assert().isEqualTo(BatchOutcome.Again)
            log.read(ref).toList().map { it.version }.assert().containsExactly(1)
            states.states[ref].assert().isNull()
        }

    @Test
    fun `a stream the backend refuses fails its command with the refusal, and the commands after it run again`() =
        runTest {
            create()
            val refusal = IllegalStateException("The store refuses the stream.")
            log.refusal = refusal
            val outcomes = batch(queued(AddItem("a", 1)), queued(AddItem("b", 1)))
            (outcomes[0] as BatchOutcome.Failed).failure.assert().isSameAs(refusal)
            outcomes[1].assert().isEqualTo(BatchOutcome.Again)
            log.read(ref).toList().map { it.version }.assert().containsExactly(1)
            states.states[ref].assert().isNull()
        }

    @Test
    fun `a batch holds at least one command, all of one aggregate`() = runTest {
        assertThrows<IllegalArgumentException> { kernel.executeBatch(cartModel, emptyList()) }
        val other = QueuedCommand(Command("other", "other", cartModel.type.ref("tenant", "cart-2"), AddItem("a", 1)))
        assertThrows<IllegalArgumentException> {
            kernel.executeBatch(
                cartModel,
                listOf(queued(AddItem("a", 1)), other)
            )
        }
    }

    private val tallyRef = AggregateType("example", "tally").ref("tenant", "tally-1")

    private fun tallied(body: Any) = QueuedCommand(
        Command("command-${++sequence}", "request-$sequence", tallyRef, body)
    )

    /** A tally whose [TallyBoom] fails deciding, as a decide function that throws does. */
    private fun tallyModel() = aggregate<Tally>("example", "tally") {
        initial { Tally(it.id) }
        decide<TallyAdd>(Creation.ALLOWED) { _, command -> Decision.events(TallyAdded(command.amount)) }
        decide<TallyBoom>(
            Creation.ALLOWED
        ) { _, command -> throw UnsupportedOperationException("boom ${command.reason}") }
        evolve<TallyAdded> { total += it.amount }
    }

    class Tally(val id: String) {
        var total = 0
    }

    data class TallyAdd(val amount: Int)
    data class TallyBoom(val reason: String)
    data class TallyAdded(val amount: Int)

    /**
     * Records the streams of each appendAll; fails it with [failure] when set, and refuses each of its streams with
     * [refusal] when set, as a backend that rejects a write it will never take answers.
     */
    private class RecordingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        val groups = mutableListOf<List<EventStreamRecord>>()
        var failure: Exception? = null
        var refusal: Exception? = null

        override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
            failure?.let { throw it }
            refusal?.let { cause -> return streams.map { AppendOutcome.Rejected(cause) } }
            groups += streams.map { it.first }
            return delegate.appendAll(streams)
        }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }
}
