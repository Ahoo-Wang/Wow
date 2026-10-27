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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandRouter
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.timer.ContextTimerFirer
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The command intake and a timer read the settlement of a command the router ran (G32): here, with the kernel's outcome,
 * or on the partition's owner, which replies only with the result.
 */
class RoutedFailuresTest {
    private val log = InMemoryEventLog()

    /** What the log answers an append, instead of appending, when not null. */
    @Volatile
    private var answer: AppendOutcome? = null

    private val failing = object : EventLog by log {
        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
            answer ?: log.append(stream, transport)
    }
    private val cart = cartModel()
    private val registry = WaitRegistry()

    /** When set, the router answers as another node would: with the result its execution settled, no outcome. */
    @Volatile
    private var remote: Boolean = false

    private val router = CommandRouter { _, _, _, local ->
        val settlement = local()
        if (remote) settlement.copy(outcome = null) else settlement
    }
    private val coordinator = WaitCoordinator(
        Executor(Kernel(failing, options = KernelOptions(maxAttempts = 2))),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
        router = router,
    )
    private val topics = InMemoryTopics(1)
    private val intake = CommandIntake(SHOP, listOf(cart), coordinator, SourceTopics(topics, "", topics, intake = true))
    private val timers = ContextTimerFirer(listOf(cart), coordinator)

    private fun wire(id: String) = ClusterCodec.wire(
        cart,
        Command(id, id, CART.ref(TENANT, "alice"), AddItems(listOf("apple")))
    )

    private fun timer(id: String, command: String = "AddItems", body: String = """{"items":["apple"]}""") = Timer(
        id = id,
        owner = CART.ref(TENANT, "owner"),
        key = "key",
        version = 1,
        partition = 0,
        rule = ScheduleRule.At(0),
        dueAt = 0,
        target = CART.ref(TENANT, "alice"),
        commandType = "shop.cart.$command",
        body = body,
    )

    @Test
    fun `the intake should fail on an unknown or contended outcome, with the kernel's cause when it ran here`(): Unit =
        runBlocking {
            answer = AppendOutcome.Unknown(IOException("The log is gone."))
            val unknown = runCatching { intake.execute(wire("c-1"), null) }.exceptionOrNull()!!
            unknown.message!!.assert().contains("c-1", ResultCodes.UNKNOWN)
            unknown.cause!!.message.assert().isEqualTo("The log is gone.")

            answer = AppendOutcome.Conflict(1)
            remote = true
            val contended = runCatching { intake.execute(wire("c-2"), null) }.exceptionOrNull()!!
            contended.message!!.assert().contains("c-2", ResultCodes.CONTENDED)
            contended.cause.assert().isNull()

            answer = null
            intake.execute(wire("c-3"), null)
        }

    @Test
    fun `a timer should fail on a technical failure and tell committed, duplicate and unchanged apart`(): Unit =
        runBlocking {
            answer = AppendOutcome.Unknown(IOException("The log is gone."))
            runCatching { timers.fire(timer("t-1")) }.exceptionOrNull()!!
                .message!!.assert().contains("t-1", ResultCodes.UNKNOWN)

            answer = null
            timers.fire(timer("t-1")).assert().isEqualTo(TimerOutcomes.COMMITTED)
            timers.fire(timer("t-1")).assert().isEqualTo(TimerOutcomes.ALREADY_COMMITTED)
            timers.fire(
                timer("t-u", "RemoveItems", """{"items":["pear"]}""")
            ).assert().isEqualTo(TimerOutcomes.UNCHANGED)
            remote = true
            // Another node's reply carries no outcome: a commit is told by its result alone.
            timers.fire(timer("t-2")).assert().isEqualTo(TimerOutcomes.COMMITTED)
            timers.fire(timer("t-2")).assert().isEqualTo(TimerOutcomes.ALREADY_COMMITTED)
        }
}
