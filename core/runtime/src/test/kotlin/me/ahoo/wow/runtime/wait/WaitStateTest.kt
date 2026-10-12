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

package me.ahoo.wow.runtime.wait

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import org.junit.jupiter.api.Test

class WaitStateTest {
    private val committed = Hop.committed("shop")
    private val saga = Hop.handled("order.saga", "shop")
    private val snapshot = Hop.snapshot("shop")
    private val command = FunctionInfo(FunctionKind.COMMAND, "shop", "order", "Place")
    private val sagaFunction = FunctionInfo(FunctionKind.SAGA, "shop", "order", "saga")
    private val snapshotFunction = FunctionInfo(FunctionKind.SNAPSHOT, "shop", "snapshot", "snapshot")

    private fun result(
        commandId: String,
        hop: Hop,
        function: FunctionInfo = command,
        derived: List<String> = emptyList(),
        error: ErrorInfo? = null,
    ) = CommandResult(
        commandId = commandId,
        requestId = "r-$commandId",
        correlationId = "root",
        ref = AggregateType("shop", "order").ref("t", commandId),
        hop = hop,
        function = function,
        status = if (error == null) CommandResult.Status.SUCCEEDED else CommandResult.Status.FAILED,
        error = error,
        derived = derived,
        signalTime = 1L,
    )

    @Test
    fun `a single hop completes with its result`() {
        val state = WaitState(listOf(snapshot), "root")
        state.offer(result("root", committed))!!.assert().isEmpty()
        state.lastReached!!.hop.assert().isEqualTo(committed)
        val done = result("root", snapshot, snapshotFunction)
        state.offer(done)!!.assert().containsExactly(done)
        state.outcome.assert().isEqualTo(done)
    }

    @Test
    fun `a later hop carries the results its command answered at COMMITTED, even when it came first`() {
        val state = WaitState(listOf(snapshot), "root")
        val done = result("root", snapshot, snapshotFunction)
        // The snapshot's signal ends the wait before the commit's own result is offered, as a fast subscription may.
        state.offer(done)!!.assert().containsExactly(done)
        state.offer(result("root", committed).copy(results = mapOf("price" to 5)))
        state.carry(done).results.assert().isEqualTo(mapOf("price" to 5))
        // Its own results, a result at COMMITTED, and another command's are left as they are.
        val own = done.copy(results = mapOf("price" to 6))
        state.carry(own).assert().isSameAs(own)
        val atCommitted = result("root", committed)
        state.carry(atCommitted).assert().isSameAs(atCommitted)
        val other = result("other", snapshot, snapshotFunction)
        state.carry(other).assert().isSameAs(other)
        // A later result at COMMITTED with none, such as a duplicate's, does not replace them.
        state.offer(result("root", committed, snapshotFunction))
        state.carry(done).results.assert().isEqualTo(mapOf("price" to 5))
    }

    @Test
    fun `a saga hop waits for every derived command, whatever order their signals come in`() {
        val state = WaitState(listOf(committed, saga, committed), "root")
        // The derived commands commit before the saga's own signal arrives: they are buffered.
        state.offer(result("d-2", committed))!!.assert().isEmpty()
        state.offer(result("root", committed))!!.map { it.commandId }.assert().containsExactly("root")
        val handled = state.offer(result("root", saga, sagaFunction, derived = listOf("d-1", "d-2")))!!
        // The saga's hop, then the buffered commit of d-2, which now belongs to the generation waited for.
        handled.map { it.commandId }.assert().containsExactly("root", "d-2")
        handled.first().derived.assert().containsExactly("d-1", "d-2")
        state.outcome.assert().isNull()
        val last = state.offer(result("d-1", committed))!!
        last.single().commandId.assert().isEqualTo("d-1")
        state.outcome!!.commandId.assert().isEqualTo("d-1")
    }

    @Test
    fun `each derived command's result is emitted as it settles, the hop completing with the last`() {
        val state = WaitState(listOf(saga, snapshot), "root")
        state.offer(result("root", saga, sagaFunction, derived = listOf("d-1", "d-2", "d-3")))!!
            .map { it.commandId }.assert().containsExactly("root")
        state.offer(result("d-2", committed))!!.assert().isEmpty()
        state.offer(result("d-2", snapshot, snapshotFunction))!!.map { it.commandId }.assert().containsExactly("d-2")
        state.offer(result("d-1", snapshot, snapshotFunction))!!.map { it.commandId }.assert().containsExactly("d-1")
        state.outcome.assert().isNull()
        // A timeout now reports the saga's hop: the last hop completed.
        state.lastReached!!.hop.assert().isEqualTo(saga)
        val last = result("d-3", snapshot, snapshotFunction)
        state.offer(last)!!.assert().containsExactly(last)
        state.outcome.assert().isEqualTo(last)
    }

    @Test
    fun `a command reaching its hop again through another function is emitted once`() {
        val state = WaitState(listOf(saga, snapshot), "root")
        state.offer(result("root", saga, sagaFunction, derived = listOf("d-1", "d-2")))
        state.offer(result("d-1", snapshot, snapshotFunction))!!.map { it.commandId }.assert().containsExactly("d-1")
        val other = FunctionInfo(FunctionKind.SNAPSHOT, "shop", "snapshot", "other")
        state.offer(result("d-1", snapshot, other))!!.assert().isEmpty()
        state.outcome.assert().isNull()
        state.offer(result("d-2", snapshot, snapshotFunction))!!.map { it.commandId }.assert().containsExactly("d-2")
        state.outcome!!.commandId.assert().isEqualTo("d-2")
    }

    @Test
    fun `a saga hop of several commands emits each and derives from all of them`() {
        val second = Hop.handled("stock.saga", "shop")
        val state = WaitState(listOf(saga, second, committed), "root")
        state.offer(result("root", saga, sagaFunction, derived = listOf("d-1", "d-2")))
        state.offer(result("d-1", second, sagaFunction, derived = listOf("e-1")))!!
            .map { it.derived }.assert().containsExactly(listOf("e-1"))
        state.offer(result("e-2", committed))!!.assert().isEmpty()
        state.offer(result("d-2", second, sagaFunction, derived = listOf("e-2")))!!
            .map { it.commandId }.assert().containsExactly("d-2", "e-2")
        state.lastReached!!.derived.assert().containsExactly("e-1", "e-2")
        state.offer(result("e-1", committed))!!.map { it.commandId }.assert().containsExactly("e-1")
        state.outcome!!.commandId.assert().isEqualTo("e-1")
    }

    @Test
    fun `a later hop of the same command arriving first is replayed after the earlier one`() {
        val state = WaitState(listOf(committed, snapshot), "root")
        state.offer(result("root", snapshot, snapshotFunction))!!.assert().isEmpty()
        state.offer(result("root", committed))!!.map { it.hop }.assert().containsExactly(committed, snapshot)
        state.outcome!!.hop.assert().isEqualTo(snapshot)
    }

    @Test
    fun `a saga deriving nothing completes the chain at once`() {
        val state = WaitState(listOf(saga, committed), "root")
        state.offer(result("root", saga, sagaFunction))!!.single().hop.assert().isEqualTo(saga)
        state.outcome!!.hop.assert().isEqualTo(saga)
    }

    @Test
    fun `a failure of a derived command fails the wait, even when it arrives early`() {
        val state = WaitState(listOf(saga, committed), "root")
        val failed = result("d-1", committed, error = ErrorInfo("stock.out", "none left"))
        state.offer(failed)!!.assert().isEmpty()
        state.offer(
            result("root", saga, sagaFunction, derived = listOf("d-1", "d-2"))
        )!!.last().assert().isEqualTo(failed)
        state.outcome.assert().isEqualTo(failed)
        state.offer(result("d-2", committed)).assert().isNull()
    }

    @Test
    fun `a signal is taken once per hop, command and function`() {
        val registry = WaitRegistry()
        val waiting = registry.register("w-1", listOf(saga, committed), "root")
        val sagaDone = result("root", saga, sagaFunction, derived = listOf("d-1", "d-2"))
        registry.signal(WaitSignal(waitId = "w-1", result = sagaDone)).assert().isTrue()
        registry.signal(WaitSignal(waitId = "w-1", result = sagaDone)).assert().isFalse()
        registry.signal(WaitSignal(waitId = "w-1", result = result("d-1", committed))).assert().isTrue()
        // The same commit signalled twice does not count for the other derived command.
        registry.signal(WaitSignal(waitId = "w-1", result = result("d-1", committed))).assert().isFalse()
        waiting.ended.assert().isFalse()
        registry.signal(WaitSignal(waitId = "w-1", result = result("d-2", committed))).assert().isTrue()
        waiting.ended.assert().isTrue()
        registry.signal(WaitSignal(waitId = "unknown", result = sagaDone)).assert().isFalse()
        registry.remove("w-1")
        registry.size.assert().isEqualTo(0)
    }
}
