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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.admissionOf
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/**
 * A command the command topic delivers names its tenant, whoever sent it (#6556): one of a type fixed to another
 * tenant is rejected as `wow.command.malformed`, with its receipt, before any aggregate decides it; one in the fixed
 * tenant runs there. A command its sender sent for the tenant its ref names ([CommandWire.actingTenant]) names none,
 * and lands in the fixed tenant; one without an acting tenant (an older writer's) names the tenant of its ref.
 */
class IntakeTenantTest {
    private val log = InMemoryEventLog()
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(log)),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin(SHOP_SERVICE, "node-1", "node-1:9090"),
        WaitOptions(),
    )
    private val topics = InMemoryTopics(1)
    private val sourceTopics = SourceTopics(topics, "", topics, intake = true)
    private val intake = CommandIntake(
        SHOP,
        listOf(cartModel()),
        coordinator,
        sourceTopics,
        admissionOf(tenants = TenantPolicy.fixed(mapOf(CART to SHARED))),
    )

    private fun wire(id: String, tenant: String, acting: String? = null) = ClusterCodec.wire(
        "shop.cart.AddItems",
        Command(id, id, CART.ref(tenant, "alice"), AddItems(listOf("apple"))),
        ReplyTo("sales", "OrderSaga.onOrderPlaced", CART.ref(tenant, "order-1"), 1),
        actingTenant = acting,
    )

    private fun receipts() = topics.messages(sourceTopics.receiptsOf("sales")).map {
        CommandMessages.rejectedOf(EnvelopeCodec.decode(it.value))
    }

    @Test
    fun `a command in the type's fixed tenant runs there, one naming another is rejected with its receipt`(): Unit =
        runBlocking {
            intake.execute(wire("c-1", SHARED), null)
            log.read(CART.ref(SHARED, "alice")).toList().size.assert().isEqualTo(1)

            intake.execute(wire("c-2", "acme"), null)
            log.read(CART.ref("acme", "alice")).toList().assert().isEmpty()
            val receipt = receipts().single()
            receipt.commandId.assert().isEqualTo("c-2")
            receipt.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            receipt.error.message.assert().isEqualTo(
                "The tenant of shop.cart is fixed to shared; the request names acme."
            )
        }

    @Test
    fun `a command sent for the tenant its ref names lands in the fixed tenant, one naming another is rejected`(): Unit =
        runBlocking {
            // A saga of acme, a timer of an acme aggregate, a caller of acme relayed: the ref's tenant is the acting one.
            intake.execute(wire("c-1", "acme", acting = "acme"), null)
            log.read(CART.ref(SHARED, "alice")).toList().size.assert().isEqualTo(1)
            log.read(CART.ref("acme", "alice")).toList().assert().isEmpty()

            intake.execute(wire("c-2", "globex", acting = "acme"), null)
            log.read(CART.ref("globex", "alice")).toList().assert().isEmpty()
            receipts().single().error.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        }

    private companion object {
        const val SHARED = "shared"
    }
}
