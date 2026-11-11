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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStateView
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStatus
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spring.boot.store.HostBackends
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean

/**
 * A payment service started in this JVM in development, with the further configuration [sources] and the [topics]
 * a test gives it, driven by its typed clients.
 */
class PaymentNode(vararg sources: Class<*>, topics: InMemoryTopics? = null) : AutoCloseable {
    private val server: WowServer = wowServer<PaymentServer>("dev", sources = sources.toList(), topics = topics)

    val context: ConfigurableApplicationContext get() = server.context

    /**
     * Over HTTP: the gateway's chargeback callback [body], as [user] (no caller when null). The callback is a mounted
     * command, the gateway's own shape on its route, which no client of the payment's contract sends.
     */
    fun chargeback(body: String, user: String? = null): Answer = server.http.post(
        "/eshop-payment/payment/chargeback-notified",
        body,
        caller = user?.let { Caller.of(subject = it) },
    )

    /** The payment [id]'s state; null before it is requested. */
    fun payment(id: String): PaymentStateView? =
        runBlocking { server.queries(Caller.ANONYMOUS).snapshots(PaymentStateView::class.java).state(id)?.state }

    /** Requests the payment of [order] and waits until the simulated gateway approved it. */
    fun succeeded(order: String, amount: String = "29.00") {
        server.charge(order, amount).status.assert().isEqualTo(PaymentStatus.SUCCEEDED)
    }

    /** The event streams of the payment [id] in the log. */
    fun streams(id: String): List<EventStreamRecord> = runBlocking {
        val log = server.bean<HostBackends>().contexts.getValue(EshopPaymentContract.NAME).log
        log.read(PAYMENT.ref(Caller.DEFAULT_TENANT, id)).toList()
    }

    override fun close() {
        server.close()
    }

    companion object {
        val PAYMENT = EshopPaymentContract.Payment.TYPE
    }
}

/**
 * Command transformation through the running payment service (decision D182): the gateway's chargeback callback,
 * [me.ahoo.wow.example.eshop.payment.domain.payment.ChargebackNotified], is mounted under the payment and adapted by
 * [Chargebacks] into the payment's refund.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentTransformationTest {
    private val payment = PaymentNode()

    @AfterAll
    fun close() {
        payment.close()
    }

    @Test
    fun `a chargeback callback should refund the payment it names, its stream naming the callback and the dispute`() {
        payment.succeeded("order-disputed")

        // The gateway knows the payment by its reference only: the callback goes to the route without an id.
        val callback = """{"merchantReference":"order-disputed","disputeId":"dispute-7","reason":"Not received"}"""
        val answer = payment.chargeback(callback)
        val result = answer.result!!
        result.status.assert().describedAs(answer.text).isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.id.assert().isEqualTo("order-disputed")
        result.transformedFrom!!.command.assert().isEqualTo(CHARGEBACK)
        payment.payment("order-disputed")!!.let { state ->
            state.status.assert().isEqualTo(PaymentStatus.REFUNDED)
            state.reason.assert().isEqualTo("Chargeback dispute-7: Not received")
        }
        val refund = payment.streams("order-disputed").last()
        refund.commandType.assert().isEqualTo("eshop-payment.payment.RefundPayment")
        refund.headers.assert().containsEntry(Chargebacks.DISPUTE_ID, "dispute-7")
            .containsEntry(CommandTransformer.TRANSFORMED_FROM, CHARGEBACK)

        // The same callback delivered again refunds nothing more: the refunded payment takes it as done.
        payment.chargeback(callback).problem.assert().isNull()
        payment.streams("order-disputed").size.assert().isEqualTo(3)
    }

    @Test
    fun `a chargeback of a payment that does not exist should be not found, naming the callback`() {
        val callback = """{"merchantReference":"order-unknown","disputeId":"dispute-8","reason":"Fraud"}"""
        val unknown = payment.chargeback(callback).problem!!
        unknown.status.assert().isEqualTo(NOT_FOUND)
        unknown.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        unknown.result!!.transformedFrom!!.command.assert().isEqualTo(CHARGEBACK)
    }

    @Test
    fun `a second transformer of the callback should stop the service from starting`() {
        val failure = runCatching { PaymentNode(SecondChargebacks::class.java).close() }.exceptionOrNull()
        checkNotNull(failure) { "The payment service started." }
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ").assert()
            .contains("More than one transformer takes $CHARGEBACK")
    }

    /** A second transformer of the callback, beside the server's. No `@Configuration`: only the test that names it. */
    class SecondChargebacks {
        @Bean
        fun secondChargebacks(): CommandTransformer = Chargebacks()
    }

    companion object {
        const val CHARGEBACK = "eshop-payment.payment.ChargebackNotified"
        private const val NOT_FOUND = 404
    }
}

/**
 * The command a transformer makes is authorized again (decision D182), on a payment service whose authorizer lets only
 * the gateway refund a payment: the callback route is anyone's to call, as a webhook's is, and the refund it becomes is
 * still the gateway's alone, so a mounted command cannot widen the caller's rights.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentTransformationAuthorizationTest {
    private val payment = PaymentNode(RefundsOnlyFromTheGateway::class.java)

    @AfterAll
    fun close() {
        payment.close()
    }

    @Test
    fun `the refund a chargeback becomes should be authorized again, so only the gateway refunds through it`() {
        payment.succeeded("order-guarded")
        val callback = """{"merchantReference":"order-guarded","disputeId":"dispute-9","reason":"Fraud"}"""

        val refused = payment.chargeback(callback, "mallory").problem!!
        refused.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        refused.result!!.function.name.assert().isEqualTo("RefundPayment")
        refused.result!!.transformedFrom!!.command.assert().isEqualTo(PaymentTransformationTest.CHARGEBACK)
        payment.payment("order-guarded")!!.status.assert().isEqualTo(PaymentStatus.SUCCEEDED)

        payment.chargeback(callback, GATEWAY).problem.assert().isNull()
        payment.payment("order-guarded")!!.status.assert().isEqualTo(PaymentStatus.REFUNDED)
    }

    /** An authorizer that lets only the [GATEWAY] refund a payment. No `@Configuration`: only the test that names it. */
    class RefundsOnlyFromTheGateway {
        @Bean
        fun refundsOnlyFromTheGateway(): CommandAuthorizer = CommandAuthorizer { caller, action ->
            if (action.type.name == "eshop-payment.payment.RefundPayment" && caller.subject != GATEWAY) {
                Authorization.Denied.of("Only the payment gateway refunds a payment.")
            } else {
                Authorization.Allowed
            }
        }
    }

    private companion object {
        const val GATEWAY = "payment-gateway"
    }
}
