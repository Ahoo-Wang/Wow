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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A batch member's after-append actions read the state its command was decided on, as [Kernel.execute] gives them:
 * never a later member's evolution, committed or not.
 */
class KernelBatchAfterAppendTest {
    class Counter {
        var total = 0
    }

    /** Adds [amount]; its after-append action puts the total it reads. Its evolution fails after adding when [fails]. */
    data class Add(val amount: Int, val fails: Boolean = false)

    /** Adds [amount], with no after-append action of its own. */
    data class Count(val amount: Int)

    /** Adds [amount] and releases a key: an after-append action of the kernel's, which reads no state. */
    data class Free(val amount: Int, val key: String)

    data class Added(val amount: Int, val fails: Boolean = false)

    private val model = aggregate<Counter>("test", "counter") {
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { state, command ->
            afterAppend { putResult(TOTAL, state.total) }
            Decision.events(Added(command.amount, command.fails))
        }
        decide<Count>(Creation.ALLOWED) { _, command -> Decision.events(Added(command.amount)) }
        decide<Free>(Creation.ALLOWED) { _, command ->
            release(KEYS, command.key)
            Decision.events(Added(command.amount))
        }
        evolve<Added> {
            total += it.amount
            check(!it.fails) { "The evolution failed after adding." }
        }
    }

    private val ref = model.type.ref("tenant", "counter-1")
    private val log = PrefixLog()
    private val kernel = Kernel(
        log,
        reservations = InMemoryReservationStore(),
        states = MapStates(),
        options = KernelOptions(recentRequests = 256),
    )
    private var sequence = 0

    private fun queued(body: Any): QueuedCommand {
        sequence++
        return QueuedCommand(Command("command-$sequence", "request-$sequence", ref, body))
    }

    private suspend fun batch(vararg bodies: Any) = kernel.executeBatch(model, bodies.map(::queued))

    private fun BatchOutcome.committed() = (this as BatchOutcome.Done).outcome as CommandOutcome.Committed

    /** The total [this] command's after-append action read, and the total its outcome's state holds now. */
    private fun BatchOutcome.totals() = committed().let { it.results[TOTAL] to (it.state as Counter).total }

    /** The versions the log holds of the counter. */
    private suspend fun versions(): List<Int> = log.read(ref).toList().map { it.version }

    @Test
    fun `a batch ends after a command whose after-append action reads the state, which runs as execute runs it`() =
        runTest {
            val first = batch(Add(1), Add(2))
            first[0].totals().assert().isEqualTo(1 to 1)
            first[1].assert().isEqualTo(BatchOutcome.Again)
            batch(Add(2))[0].totals().assert().isEqualTo(3 to 3)
            log.calls.assert().containsExactly(1, 1)
        }

    @Test
    fun `a command after it whose evolution fails leaves its after-append action the committed state`() = runTest {
        val outcomes = batch(Add(1), Add(2, fails = true))
        outcomes[0].totals().assert().isEqualTo(1 to 1)
        outcomes[1].assert().isEqualTo(BatchOutcome.Again)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `a command after it whose stream is refused leaves its after-append action the committed state`() = runTest {
        log.refuseAfterFirst = true
        val outcomes = batch(Add(1), Add(2))
        outcomes[0].totals().assert().isEqualTo(1 to 1)
        outcomes[1].assert().isEqualTo(BatchOutcome.Again)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `commands without after-append actions of their own, the kernel's aside, commit with one append`() = runTest {
        val outcomes = batch(Count(1), Free(2, "a"), Count(3))
        outcomes.map { it.committed().stream.version }.assert().containsExactly(1, 2, 3)
        log.calls.assert().containsExactly(3)
        // Its after-append action reads its own state: the batch's commands before it evolved it, none after it.
        batch(Count(1), Add(1), Count(1)).let { mixed ->
            mixed[1].totals().assert().isEqualTo(8 to 8)
            mixed[2].assert().isEqualTo(BatchOutcome.Again)
        }
        log.calls.assert().containsExactly(3, 2)
    }

    /**
     * The in-memory log, recording how many streams each call holds; with [refuseAfterFirst], each stream after a
     * call's first is refused, as a backend that commits a prefix answers it.
     */
    private class PrefixLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        val calls = mutableListOf<Int>()
        var refuseAfterFirst = false

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            calls += streams.size
            if (!refuseAfterFirst || streams.size == 1) return delegate.appendAll(streams)
            return delegate.appendAll(streams.take(1)) +
                streams.drop(1).map { AppendOutcome.Rejected(IOException("refused")) }
        }
    }

    private class MapStates : StateCache {
        private val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private companion object {
        const val TOTAL = "total"
        const val KEYS = "keys"
    }
}
