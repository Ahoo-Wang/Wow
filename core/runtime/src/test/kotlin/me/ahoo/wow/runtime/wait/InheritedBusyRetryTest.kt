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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.ExecutorBusyException
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Deep review r1 (runtime wait): a command that inherits a wait (a topic intake's root command, a saga's derived one)
 * and that the owner's executor refuses as busy. `InheritedWaits.forward` signals the refusal to the waiting node as
 * the command's result at COMMITTED, FAILED BUSY, which ends the wait (`WaitState.apply`: any status but SUCCEEDED).
 * Its source takes BUSY as backpressure and runs the command again (`CommandIntake`: `busyOf` is thrown and the
 * subscription retries; `DerivedCommands.failureOf` likewise), and the retry commits: its SUCCEEDED signal has the same
 * `SignalKey` (hop, command, function) and is dropped. The waiter is answered FAILED BUSY for a command that committed.
 */
class InheritedBusyRetryTest {
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
    private val clock = Clock.systemUTC()

    /** The waiting node's registry, which the owner's signals reach in process. */
    private val registry = WaitRegistry()
    private val waiter = Origin("node-1", "node-1:9090", "shop")

    /** The executor refuses the first attempt as busy, and commits the next. */
    private val attempts = AtomicInteger()
    private val executor = mockk<Executor> {
        coEvery { execute(any(), any(), any()) } coAnswers {
            if (attempts.incrementAndGet() == 1) throw ExecutorBusyException(command.ref, 1)
            CommandOutcome.Committed(stream, LogPosition(0, 1), Any())
        }
    }
    private val directory = mockk<SubscriptionDirectory> {
        every { kindOf(any(), any()) } returns FunctionKind.SYSTEM
        coEvery { hasHandled(any(), any()) } returns false
    }
    private val executions = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inherited = InheritedWaits(
        CommandRouter.LOCAL,
        LocalExecution(executor, directory, Observer.NONE, clock) { Origin("node-0", "node-0:9090", "shop") },
        LocalSignalSender(waiter.node, registry),
        directory,
        HeldDeadlines(),
        WaitOptions(),
        clock,
        Observer.NONE,
        executions,
    )

    @AfterEach
    fun stop() {
        executions.cancel()
    }

    @Test
    fun `a command refused as busy and run again by its source should answer its commit, not the refusal`(): Unit =
        runBlocking {
            val waiting = checkNotNull(registry.register("w-1", listOf(Hop.committed("shop")), command.id))
            // As the coordinator sent it through the topic: ACCEPTED once the broker took it.
            waiting.offer(reachedResult(command, commandFunction(model, command), Hop.accepted("shop"), clock.millis()))
            val wait = WaitContext("w-1", waiter, listOf(Hop.committed("shop")), Long.MAX_VALUE)
            // The owner's intake: refused as busy, then, retried as backpressure, committed.
            inherited.forward(model, command, wait).committed.error?.code.assert().isEqualTo(ResultCodes.BUSY)
            inherited.forward(model, command, wait).committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val answered = generateSequence { waiting.results.tryReceive().getOrNull() }.toList()
            val last = answered.last()
            last.status.assert()
                .describedAs("the wait's answer at ${last.hop}: ${last.error?.code}; the command committed once retried")
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
}
