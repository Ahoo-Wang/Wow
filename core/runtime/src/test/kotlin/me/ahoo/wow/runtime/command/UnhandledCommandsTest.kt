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
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A command of a type its context does not know (review X-5), executed by the intake directly: it is rejected with a
 * receipt, and the node waiting on it is signalled the rejection at its context's COMMITTED hop while the wait lasts.
 */
class UnhandledCommandsTest {
    private val cart = cartModel()
    private val origin = Origin("node-0", "node-0:9090", SHOP_SERVICE)
    private val signalled = CopyOnWriteArrayList<WaitSignal>()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog())),
        WaitRegistry(),
        SubscriptionDirectory.NONE,
        SignalSender { _, signal -> signalled += signal },
        WaitOptions(Origin("node-1", "node-1:9090", SHOP_SERVICE)),
    )
    private val topics = InMemoryTopics(1)
    private val sourceTopics = SourceTopics(topics, "", topics, intake = true)
    private val intake = CommandIntake(SHOP, listOf(cart), coordinator, sourceTopics, clock = { NOW })

    private fun wire(causation: Causation? = null): CommandWire = ClusterCodec.wire(
        "shop.cart.AddItemsV2",
        Command("c-1", "r-1", CART.ref(TENANT, "alice"), AddItems(listOf("apple")), causation = causation),
        ReplyTo("sales", "OrderSaga.onOrderPlaced", CART.ref(TENANT, "order-1"), 1),
    )

    private fun wait(vararg remaining: Hop, deadline: Long = NOW + 1_000) =
        WaitContext("w-1", origin, remaining.toList(), deadline)

    private fun receipts() = topics.messages(sourceTopics.receiptsOf("sales"))

    @Test
    fun `a wait with no COMMITTED hop before its next HANDLED one should be signalled at the context's COMMITTED`(): Unit =
        runBlocking {
            intake.execute(wire(), wait(Hop.handled("OrderSaga.onRejected", "sales")))
            val result = signalled.single().result
            result.hop.assert().isEqualTo(Hop.committed(SHOP))
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.error!!.code.assert().isEqualTo(FrameworkCodes.UNHANDLED)
            // A command no saga derived is its own chain's root.
            result.correlationId.assert().isEqualTo("c-1")
            result.function.name.assert().isEqualTo("AddItemsV2")
            receipts().size.assert().isEqualTo(1)
        }

    @Test
    fun `a wait should be signalled at the COMMITTED hop its plan names, in the chain of the command's root`(): Unit =
        runBlocking {
            val causation = Causation("root-1", "sales.order/t/order-1@1")
            intake.execute(wire(causation), wait(Hop.accepted(SHOP), Hop.committed(), Hop.handled("Projector.on")))
            val result = signalled.single().result
            result.hop.assert().isEqualTo(Hop.committed())
            result.correlationId.assert().isEqualTo("root-1")
        }

    @Test
    fun `a wait that is over should not be signalled, and the receipt should still be sent`(): Unit = runBlocking {
        intake.execute(wire(), wait(Hop.committed(SHOP), deadline = NOW - 1))
        signalled.assert().isEmpty()
        CommandMessages.rejectedOf(EnvelopeCodec.decode(receipts().single().value))
            .error.code.assert().isEqualTo(FrameworkCodes.UNHANDLED)
    }

    @Test
    fun `a command from another context in another tenant than its type's fixed one should be refused`(): Unit =
        runBlocking {
            // DX gap G13: the cart's tenant is fixed; the sender's tenant crosses with its command.
            val fixed = CommandIntake(
                SHOP,
                listOf(cart),
                coordinator,
                sourceTopics,
                clock = { NOW },
                tenants = TenantPolicy.fixed(mapOf(CART to "platform")),
            )
            val known = ClusterCodec.wire(
                "shop.cart.AddItems",
                Command("c-2", "r-2", CART.ref(TENANT, "alice"), AddItems(listOf("apple"))),
                ReplyTo("sales", "OrderSaga.onOrderPlaced", CART.ref(TENANT, "order-1"), 1),
            )
            fixed.execute(known, wait(Hop.committed(SHOP)))
            val result = signalled.single().result
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.error!!.code.assert().isEqualTo(FrameworkCodes.MALFORMED)
            result.error!!.message.assert().contains("fixed to platform").contains("names $TENANT")
            CommandMessages.rejectedOf(EnvelopeCodec.decode(receipts().single().value))
                .error.code.assert().isEqualTo(FrameworkCodes.MALFORMED)
        }

    private companion object {
        const val NOW = 1_000_000L
    }
}
