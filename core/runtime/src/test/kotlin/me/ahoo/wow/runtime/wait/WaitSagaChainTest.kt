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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Waits through a saga: a chain completes once every derived command has, each hop after the saga answers once per
 * derived command, and a rejection, a timeout or a saga deriving nothing ends it where it is.
 */
class WaitSagaChainTest : WaitCoordinatorFixtures() {
    @Test
    fun `a saga chain completes only when every derived command has committed`(): Unit = runBlocking {
        started()
        val gate = CompletableDeferred<Unit>().also { sagaGate = it }
        val command = place(items = 2, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        val emitted = CopyOnWriteArrayList<CommandResult>()
        val done = background.async { coordinator.stream(order, command).collect { emitted += it } }
        // The first derived command commits before the saga's own signal arrives: it is buffered until then, and
        // emitted right after the saga's hop.
        eventually { emitted.size == 2 }
        val sagaHop = emitted.first()
        sagaHop.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        sagaHop.function.kind.assert().isEqualTo(FunctionKind.SAGA)
        sagaHop.derived.assert().containsExactly("${command.id}-reserve-1", "${command.id}-reserve-2")
        emitted[1].commandId.assert().isEqualTo("${command.id}-reserve-1")
        emitted[1].hop.assert().isEqualTo(Hop.committed("shop"))
        done.isCompleted.assert().isFalse()
        gate.complete(Unit)
        withTimeout(5.seconds) { done.await() }
        emitted.size.assert().isEqualTo(3)
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        last.hop.assert().isEqualTo(Hop.committed("shop"))
        last.commandId.assert().isEqualTo("${command.id}-reserve-2")
        last.correlationId.assert().isEqualTo(command.id)
        last.ref.type.assert().isEqualTo(stock.type)
    }

    @Test
    fun `the stream emits one result per derived command at each hop after the saga`(): Unit = runBlocking {
        started()
        val command = place(items = 3, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.snapshot()))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        emitted.size.assert().isEqualTo(4)
        emitted.first().hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        val derived = (1..3).map { "${command.id}-reserve-$it" }
        emitted.first().derived.assert().containsExactly(*derived.toTypedArray())
        emitted.drop(1).forEach {
            it.hop.assert().isEqualTo(Hop.snapshot("shop"))
            it.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        emitted.drop(1).map { it.commandId }.assert().containsExactlyInAnyOrder(*derived.toTypedArray())
        // The answer without streaming is the last command to settle.
        val answered = coordinator.execute(
            order,
            place(items = 2, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.snapshot()))
        )
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.ref.type.assert().isEqualTo(stock.type)
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a derived command's rejection ends the stream at once, as its last result`(): Unit = runBlocking {
        started()
        rejectedItem = 2
        val command = place(items = 3, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        // The saga forwards its commands in order, so their results arrive in order and the third is never applied.
        emitted.map { it.commandId }.assert().containsExactly(
            command.id,
            "${command.id}-reserve-1",
            "${command.id}-reserve-2",
        )
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.error!!.code.assert().isEqualTo("shop.stock.out")
        last.hop.assert().isEqualTo(Hop.committed("shop"))
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a timeout while derived commands settle ends the stream at the saga hop`(): Unit = runBlocking {
        started()
        sagaGate = CompletableDeferred()
        val command = place(items = 2, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        val emitted = held.timedOut(holding.stream(order, command)) { it.commandId == "${command.id}-reserve-1" }
        emitted.map { it.commandId }.assert().containsExactly(
            command.id,
            "${command.id}-reserve-1",
            command.id,
        )
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.FAILED)
        last.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        last.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        last.derived.assert().containsExactly("${command.id}-reserve-1", "${command.id}-reserve-2")
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a saga deriving nothing ends the chain early at its own hop`(): Unit = runBlocking {
        started()
        val result = coordinator.execute(
            order,
            place(items = 0, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        )
        result.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.derived.assert().isEmpty()
        // The chain ended before its plan did (D212): the hop it did not reach is named.
        result.chainEnded.assert().isTrue()
        result.pending.assert().containsExactly(Hop.committed("shop"))
    }

    @Test
    fun `a chain may go on to the snapshot of the derived commands`(): Unit = runBlocking {
        started()
        val command = place(items = 1, wait = WaitPlan.of(Hop.committed(), Hop.handled("order.saga"), Hop.snapshot()))
        val hops = coordinator.stream(order, command).toList()
        hops.map { it.hop.stage }.assert().containsExactly(Stage.COMMITTED, Stage.HANDLED, Stage.HANDLED)
        hops.last().function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        hops.last().commandId.assert().isEqualTo("${command.id}-reserve-1")
        snapshotted.assert().contains("${command.ref.id}-1@1")
        // Each hop answers the results of its own command: the order's at COMMITTED and at its saga's hop, the derived
        // one's at its snapshot.
        hops[0].results.assert().isEqualTo(mapOf(ITEMS to 1))
        hops[1].results.assert().isEqualTo(mapOf(ITEMS to 1))
        hops.last().results.assert().isEqualTo(mapOf(ORDER to command.ref.id))
    }

    @Test
    fun `the stream emits each hop as it is reached`(): Unit = runBlocking {
        started()
        val hops = coordinator.stream(order, place(wait = WaitPlan.of(Hop.accepted(), Hop.committed(), Hop.snapshot())))
            .toList()
        hops.map { it.hop }.assert().containsExactly(Hop.accepted("shop"), Hop.committed("shop"), Hop.snapshot("shop"))
        hops[0].version.assert().isNull()
        hops[1].version.assert().isEqualTo(1)
    }

    @Test
    fun `a timeout reports the last hop reached`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val results = holding.stream(order, place(wait = WaitPlan.of(Hop.committed(), Hop.snapshot())))
        val result = held.timedOut(results) { it.hop.stage == Stage.COMMITTED }.last()
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.version.assert().isEqualTo(1)
        registry.size.assert().isEqualTo(0)
    }
}
