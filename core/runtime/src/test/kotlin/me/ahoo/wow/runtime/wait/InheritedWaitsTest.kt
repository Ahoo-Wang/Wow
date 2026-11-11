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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The commands that inherit a wait, on their own: what a derived command signals to the node that waits, a call's
 * answer by its deadline, and a signal that cannot be sent. The node runs the command itself ([CommandRouter.LOCAL]).
 */
class InheritedWaitsTest {
    data class Place(val amount: Int)

    private val model = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, _ -> Decision.NoOp }
    }
    private val command: Command<Any> =
        Command("c-1", "r-1", model.type.ref("t", "o-1"), Place(1), wait = WaitPlan.COMMITTED.within(5.seconds))
    private val stream = EventStreamRecord(
        command.ref,
        1,
        "c-1",
        "r-1",
        "shop.order.Place",
        0L,
        listOf(EventRecord("shop.order.Placed", 1, "{}")),
        Scope.NONE,
    )
    private val origin = Origin("node-0", "127.0.0.1:9090", "shop")
    private val signalled = CopyOnWriteArrayList<CommandResult>()

    @Volatile
    private var refusing: Throwable? = null
    private val signals = SignalSender { _, signal, _ ->
        refusing?.let { throw it }
        signalled += signal.result
    }

    @Volatile
    private var outcome: suspend () -> CommandOutcome = { CommandOutcome.Committed(stream, LogPosition(0, 1), Any()) }
    private val executor = mockk<Executor> { coEvery { execute(any(), any(), any()) } coAnswers { outcome() } }
    private val directory = mockk<SubscriptionDirectory> {
        every { kindOf(any(), any()) } returns FunctionKind.SYSTEM
        coEvery { hasHandled(any(), any()) } returns false
    }
    private val clock = Clock.systemUTC()
    private val deadlines = HeldDeadlines()
    private val executions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val local = LocalExecution(executor, directory, Observer.NONE, clock) { origin }
    private val inherited = InheritedWaits(
        CommandRouter.LOCAL,
        local,
        signals,
        directory,
        deadlines,
        WaitOptions(),
        clock,
        Observer.NONE,
        executions,
    )

    @AfterEach
    fun stop() {
        executions.cancel()
    }

    private fun waitFor(vararg hops: Hop, deadline: Long = Long.MAX_VALUE) =
        WaitContext("w-1", Origin("node-1", "node-1:9090", "shop"), hops.toList(), deadline)

    @Test
    fun `a command without a wait, past its deadline, or expected in another context only executes`(): Unit =
        runBlocking {
            inherited.forward(model, command, null).committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            inherited.forward(model, command, waitFor(Hop.committed("shop"), deadline = 0L))
            inherited.forward(model, command, waitFor(Hop.committed("stock")))
            signalled.assert().isEmpty()
        }

    @Test
    fun `a derived command signals ACCEPTED when the wait asks for it, then its commit at the hop it names`(): Unit =
        runBlocking {
            // A hop that names no context is of the command's.
            inherited.forward(model, command, waitFor(Hop.accepted(), Hop.committed("shop")))
            signalled.map { it.hop.stage }.assert().containsExactly(Stage.ACCEPTED, Stage.COMMITTED)
        }

    @Test
    fun `a derived command whose wait names no commit is signalled at its context's, then at the hop it reached`():
        Unit = runBlocking {
        outcome = { CommandOutcome.Unchanged(1) }
        inherited.forward(model, command, waitFor(Hop.handled("wow.snapshot", "shop")))
        signalled.map { it.hop }.assert().containsExactly(
            Hop.committed("shop"),
            Hop.handled("wow.snapshot", "shop")
        )
    }

    @Test
    fun `a signal that cannot be sent is logged, and the command's result stands`(): Unit = runBlocking {
        refusing = IllegalStateException("The waiting node is gone.")
        val settlement = inherited.forward(model, command, waitFor(Hop.committed("shop")))
        settlement.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        signalled.assert().isEmpty()
        refusing = CancellationException("The node stops.")
        assertThrows<CancellationException> { inherited.signal(waitFor(Hop.committed("shop")), settlement.committed) }
    }

    @Test
    fun `a call is answered at its commit when the execution settles in time`(): Unit = runBlocking {
        val result = inherited.call(model, command, null)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.stage.assert().isEqualTo(Stage.COMMITTED)
    }

    @Test
    fun `a call whose deadline passes first times out at ACCEPTED, and its execution goes on`(): Unit = runBlocking {
        val held = CompletableDeferred<Unit>()
        val executing = CompletableDeferred<Unit>()
        outcome = {
            executing.complete(Unit)
            held.await()
            CommandOutcome.Committed(stream, LogPosition(0, 1), Any())
        }
        val answer = async { inherited.call(model, command, null) }
        withTimeout(5.seconds) { executing.await() }
        deadlines.pass()
        val result = withTimeout(5.seconds) { answer.await() }
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        held.complete(Unit)
    }
}
