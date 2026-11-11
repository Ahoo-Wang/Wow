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

package me.ahoo.wow.example.eshop.payment.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStateView
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStatus
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.math.BigDecimal

/**
 * The payment service as its `application.yaml` configures it, on random ports, with a gateway limit of 100.00. No
 * ordering runs: the test requests the payments itself, as the order-payment saga would once it read an order's
 * confirmed stock from the ordering's stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServerTest {
    private val server = wowServer<PaymentServer>("dev", properties = mapOf("eshop.payment.gateway.limit" to "100.00"))

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the server should run one node of the payment context with the ordering as its peer`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-payment-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-payment")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }
            .assert().containsExactly("payment")
        // The ordering's contract is read from the jar of the ordering api; the payment's own, in the payment api's jar,
        // is no peer of the node's own context (decision D234).
        val contracts = server.bean<ContractDirectory>()
        contracts.contextOf("eshop-ordering").assert().isNotNull()
        contracts.peers.single().source.assert().contains("eshop-ordering-api").endsWith(PeerContracts.RESOURCE)
    }

    @Test
    fun `a payment within the gateway's limit should succeed`() {
        val payment = server.charge("order-approved", "29.00")
        payment.status.assert().isEqualTo(PaymentStatus.SUCCEEDED)
        payment.transactionId.assert().startsWith("simulated-")
    }

    @Test
    fun `a payment over the gateway's limit should fail`() {
        val payment = server.charge("order-declined", "150.00")
        payment.status.assert().isEqualTo(PaymentStatus.FAILED)
        payment.reason.assert().contains("over the limit of 100.00")
    }

    @Test
    fun `the gateway should be down while its outage file exists`(): Unit = runBlocking {
        val outage = File.createTempFile("gateway", ".down").apply { deleteOnExit() }
        val gateway = PaymentServer().paymentGateway(BigDecimal("100.00"), outage.path)
        assertThrows<IllegalStateException> {
            runBlocking { gateway.charge("p-1", BigDecimal.ONE, "1 × mug", "k-1") }
        }
        outage.delete()
        gateway.charge("p-1", BigDecimal.ONE, "1 × mug", "k-1").toString().assert().contains("simulated-k-1")
        PaymentServer().paymentGateway(BigDecimal.ONE, "").charge("p-2", BigDecimal.ONE, "1 × mug", "k-2")
            .toString().assert().contains("simulated-k-2")
    }

    @Test
    fun `a payment of nothing should be invalid before the payment decides`() {
        val nothing = RequestPayment("order-free", BigDecimal.ZERO)
        val invalid = assertThrows<CommandRejectedException> {
            runBlocking { server.commands(Caller.ANONYMOUS).wait(nothing, "order-free") }
        }
        invalid.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        invalid.result.error!!.violations.single().let {
            it.path.assert().isEqualTo("amount")
            it.code.assert().isEqualTo("positive")
        }
    }
}

/**
 * The plan of a payment's request to the end of its gateway's chain: the gateway saga charges the payment, and the
 * command it derives (`ConfirmPayment` or `FailPayment`) is in the payment's snapshot.
 */
val TO_THE_GATEWAY: WaitPlan = WaitPlan.of(
    Hop.handled("PaymentGatewaySaga.onPaymentRequested", EshopPaymentContract.NAME),
    Hop.snapshot(EshopPaymentContract.NAME),
)

/** Requests the payment of [amount] for [order] and waits to the end of its gateway's chain: the payment it became. */
fun WowServer.charge(order: String, amount: String): PaymentStateView = runBlocking {
    val command = RequestPayment(order, BigDecimal(amount))
    val request = CommandRequest.builder(command).id(order).wait(TO_THE_GATEWAY).build()
    commands(Caller.ANONYMOUS).wait(request)
    queries(Caller.ANONYMOUS).snapshots(PaymentStateView::class.java).state(order)!!.state
}
