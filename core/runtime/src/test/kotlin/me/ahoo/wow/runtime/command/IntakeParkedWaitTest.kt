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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.admissionOf
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.clockOf
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.SourcedEntry
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A command the intake parks after its retries (#6757): its wait, while it lasts, ends FAILED parked at the command's
 * COMMITTED hop, since no signal told it of the technical failures the intake retried; a command without a wait, or
 * whose wait is over, is told nothing.
 */
class IntakeParkedWaitTest {
    private val origin = Origin("node-0", "node-0:9090", SHOP_SERVICE)
    private val signalled = CopyOnWriteArrayList<WaitSignal>()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog())),
        WaitRegistry(),
        SubscriptionDirectory.NONE,
        SignalSender { _, signal, _ -> signalled += signal },
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )
    private val topics = InMemoryTopics(1)
    private val intake = CommandIntake(
        SHOP,
        listOf(cartModel()),
        coordinator,
        SourceTopics(topics, "", topics, intake = true),
        admissionOf(),
        clock = clockOf { NOW },
    )
    private val parked = checkNotNull(intake.subscription.parked)

    private fun entry(transport: Transport?, message: Any? = wire()) = SourcedEntry(
        LogPosition(0, 1),
        EventStreamRecord(
            CART.ref(TENANT, "alice"),
            1,
            "c-1",
            "r-1",
            "shop.cart.AddItems",
            0L,
            listOf(EventRecord("shop.cart.ItemsAdded", 1, "{}")),
            Scope.NONE,
        ),
        transport,
        message = message,
    )

    private fun wire() = ClusterCodec.wire(
        "shop.cart.AddItems",
        Command("c-1", "r-1", CART.ref(TENANT, "alice"), AddItems(listOf("apple"))),
    )

    private fun waiting(deadline: Long = NOW + 1_000) =
        Transport(WaitContext("w-1", origin, listOf(Hop.committed(), Hop.snapshot()), deadline))

    @Test
    fun `a command parked while its wait lasts ends that wait failed parked at the command's COMMITTED hop`(): Unit =
        runBlocking {
            parked(entry(waiting()), IllegalStateException("The store is down: secret-host:5432"))
            val result = signalled.single().result
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            // The cause stays in the ledger and the logs.
            result.error!!.message.assert().doesNotContain("secret-host")
            result.hop.assert().isEqualTo(Hop.committed())
            result.commandId.assert().isEqualTo("c-1")
        }

    @Test
    fun `a command parked with no wait, a wait that is over, or no command at all is told nothing`(): Unit =
        runBlocking {
            val failure = IllegalStateException("The store is down.")
            parked(entry(null), failure)
            parked(entry(Transport()), failure)
            parked(entry(waiting(deadline = NOW - 1)), failure)
            parked(entry(waiting(), message = null), failure)
            signalled.assert().isEmpty()
        }

    private companion object {
        const val NOW = 1_000_000L
    }
}
