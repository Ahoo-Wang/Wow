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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A command still queued in its aggregate's lane when its wait times out is withdrawn (decision D228 ②): it never
 * enters the kernel, and a retry with the same request id runs it afresh.
 */
class WaitWithdrawalTest {
    data class Hold(val note: String = "")
    data class Add(val note: String = "")
    data class Added(val note: String)

    private val deciding = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    private val model = aggregate<Any>("shop", "order") {
        initial { Any() }
        decideSuspending<Hold>(Creation.ALLOWED) { _, _ ->
            deciding.complete(Unit)
            gate.await()
            Decision.events(Added("held"))
        }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.note)) }
        evolve<Added> { }
    }

    private val log = InMemoryEventLog(partitions = 2)
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val directory = object : SubscriptionDirectory {
        override fun kindOf(context: String, subscription: String): FunctionKind? = null

        override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
    }
    private val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val observer = object : Observer {
        override fun refused(type: AggregateType, code: String) {
            observed += "refused $code"
        }

        override fun waitTimedOut(type: AggregateType, stage: Stage) {
            observed += "timed out at $stage"
        }
    }
    private val coordinator = coordinator()

    /**
     * A coordinator whose executor reads a clock [ahead] of the coordinator's: a lane wait then reaches its deadline
     * that much before the deadline thread does (review X-12).
     */
    private fun coordinator(ahead: Long = 0) = WaitCoordinator(
        Executor(Kernel(log), clock = { System.currentTimeMillis() + ahead }),
        registry,
        directory,
        LocalSignalSender(origin.node, registry),
        WaitOptions(origin),
        observer = observer,
    )

    private fun command(id: String, body: Any, requestId: String, wait: WaitPlan) =
        Command(id, requestId, model.type.ref("t", "o-1"), body, wait = wait)

    @Test
    fun `a command still queued in its lane when its wait times out is withdrawn and never executes`(): Unit =
        runBlocking {
            withdraws(coordinator)
        }

    @Test
    fun `a command withdrawn before the deadline thread ends its wait is answered as the deadline answers it`():
        Unit = runBlocking {
        // Review X-12: the lane's timer fires first, by the clock's lead; the answer is the same.
        val withdrawnFirst = coordinator(ahead = 10_000)
        val timedOut = withdraws(withdrawnFirst)
        timedOut.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        timedOut.error!!.message.assert().isEqualTo("The wait timed out at accepted(shop), before the command's outcome was known.")
    }

    /**
     * Holds the lane with one command, times out a second one queued behind it, and checks that it was withdrawn: it
     * never ran, the wait timed out at ACCEPTED, and a retry runs it. The answer of the timed-out wait.
     */
    private suspend fun withdraws(coordinator: WaitCoordinator): CommandResult = coroutineScope {
        val first = async(Dispatchers.Default) {
            coordinator.execute(model, command("c-1", Hold(), "r-1", WaitPlan.COMMITTED))
        }
        withTimeout(5.seconds) { deciding.await() }
        val queued = command("c-2", Add("queued"), "r-2", WaitPlan.COMMITTED.within(200.milliseconds))
        val timedOut = withTimeout(5.seconds) { coordinator.execute(model, queued) }
        timedOut.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        timedOut.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        // The withdrawal settles its execution while the lane is still held.
        withTimeout(5.seconds) {
            while (coordinator.executing > 1) {
                delay(10)
            }
        }

        gate.complete(Unit)
        withTimeout(5.seconds) { first.await() }.version.assert().isEqualTo(1)
        withTimeout(5.seconds) { coordinator.join() }
        log.findByRequestId("t", "r-2").assert().isNull()
        // Review A5-11: the timeout and the withdrawal are both observed, whichever noticed the deadline first.
        observed.assert().containsExactlyInAnyOrder("timed out at ACCEPTED", "refused ${ResultCodes.WAIT_TIMEOUT}")
        val retry = coordinator.execute(model, command("c-3", Add("queued"), "r-2", WaitPlan.COMMITTED))
        retry.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        retry.duplicate.assert().isFalse()
        retry.version.assert().isEqualTo(2)
        timedOut
    }
}
