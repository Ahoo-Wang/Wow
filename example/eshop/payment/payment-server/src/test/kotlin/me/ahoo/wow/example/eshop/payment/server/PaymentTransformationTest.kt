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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.eshop.payment.api.PaymentContext
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A payment service started in this JVM with its [topics] in memory and the further configuration [sources], called
 * over HTTP on its public port; `X-User` names the caller when a source resolves callers from it.
 */
class PaymentNode(
    vararg sources: Class<*>,
    topics: Class<*> = InMemoryPaymentTopics::class.java,
) : AutoCloseable {
    val context: ConfigurableApplicationContext =
        SpringApplicationBuilder(PaymentServer::class.java, topics, *sources).run(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
            "--wow.cluster.drain-timeout=1s",
            "--wow.subscription.stop-timeout=2s",
        )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    /** POSTs [body] to [path] as [user] (no `X-User` when null): the status and the body. */
    fun post(path: String, body: String, user: String? = null): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        user?.let { request.header(USER, it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    fun state(id: String): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(uri("/eshop-payment/payment/$id/state")).timeout(TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == OK) { "The state of $id answered ${response.statusCode()}: ${response.body()}" }
        return json.readTree(response.body())["state"]
    }

    /** Requests the payment of [order] and waits until the simulated gateway approved it. */
    fun succeeded(order: String, amount: String = "29.00") {
        post("/eshop-payment/payment/$order/request-payment", """{"orderId":"$order","amount":$amount}""")
            .first.assert().isEqualTo(OK)
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (state(order)["status"].asString() != "SUCCEEDED") {
            check(System.nanoTime() < deadline) { "The payment of $order did not succeed in $DEADLINE." }
            Thread.sleep(POLL.toMillis())
        }
    }

    /** The event streams of the payment [id] in the log. */
    fun streams(id: String): List<EventStreamRecord> = runBlocking {
        val log = context.getBean(HostBackends::class.java).contexts.getValue(PaymentContext.NAME).log
        log.read(PAYMENT.ref(Caller.DEFAULT_TENANT, id)).toList()
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    override fun close() {
        try {
            context.close()
        } finally {
            http.shutdownNow()
        }
    }

    companion object {
        const val USER = "X-User"
        const val OK = 200
        val PAYMENT = AggregateType(PaymentContext.NAME, PaymentContext.PAYMENT)
        private val json: JsonMapper = JsonMapper.builder().build()
        private val TIMEOUT: Duration = Duration.ofSeconds(30)
        private val DEADLINE: Duration = Duration.ofSeconds(20)
        private val POLL: Duration = Duration.ofMillis(100)
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
        val (status, result) = payment.post(
            "/eshop-payment/payment/chargeback-notified",
            """{"merchantReference":"order-disputed","disputeId":"dispute-7","reason":"Not received"}""",
        )
        status.assert().describedAs(result.toString()).isEqualTo(PaymentNode.OK)
        result["ref"]["id"].asString().assert().isEqualTo("order-disputed")
        result["transformedFrom"]["command"].asString().assert().isEqualTo(CHARGEBACK)
        payment.state("order-disputed").let { state ->
            state["status"].asString().assert().isEqualTo("REFUNDED")
            state["reason"].asString().assert().isEqualTo("Chargeback dispute-7: Not received")
        }
        val refund = payment.streams("order-disputed").last()
        refund.commandType.assert().isEqualTo("eshop-payment.payment.RefundPayment")
        refund.headers.assert().containsEntry(Chargebacks.DISPUTE_ID, "dispute-7")
            .containsEntry(CommandTransformer.TRANSFORMED_FROM, CHARGEBACK)

        // The same callback delivered again refunds nothing more: the refunded payment takes it as done.
        payment.post(
            "/eshop-payment/payment/chargeback-notified",
            """{"merchantReference":"order-disputed","disputeId":"dispute-7","reason":"Not received"}""",
        ).first.assert().isEqualTo(PaymentNode.OK)
        payment.streams("order-disputed").size.assert().isEqualTo(3)
    }

    @Test
    fun `a chargeback of a payment that does not exist should be not found, naming the callback`() {
        val (status, problem) = payment.post(
            "/eshop-payment/payment/chargeback-notified",
            """{"merchantReference":"order-unknown","disputeId":"dispute-8","reason":"Fraud"}""",
        )
        status.assert().isEqualTo(NOT_FOUND)
        problem["code"].asString().assert().isEqualTo("wow.aggregate.not-found")
        problem["result"]["transformedFrom"]["command"].asString().assert().isEqualTo(CHARGEBACK)
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

        val (status, refused) = payment.post("/eshop-payment/payment/chargeback-notified", callback, "mallory")
        status.assert().isEqualTo(FORBIDDEN)
        refused["code"].asString().assert().isEqualTo("wow.auth.forbidden")
        refused["result"]["function"]["name"].asString().assert().isEqualTo("RefundPayment")
        refused["result"]["transformedFrom"]["command"].asString().assert()
            .isEqualTo(PaymentTransformationTest.CHARGEBACK)
        payment.state("order-guarded")["status"].asString().assert().isEqualTo("SUCCEEDED")

        payment.post("/eshop-payment/payment/chargeback-notified", callback, GATEWAY).first
            .assert().isEqualTo(PaymentNode.OK)
        payment.state("order-guarded")["status"].asString().assert().isEqualTo("REFUNDED")
    }

    /**
     * The caller `X-User` names, of the default tenant and acting in no owner's scope, and an authorizer that lets only
     * the [GATEWAY] refund a payment. No `@Configuration`: only the test that names it.
     */
    class RefundsOnlyFromTheGateway {
        @Bean
        fun headerCallers(): CallerResolver = CallerResolver { request ->
            request.headers().firstHeader(PaymentNode.USER)?.let { user ->
                Caller.of(tenantId = Caller.DEFAULT_TENANT, scope = Scope.NONE, subject = user)
            } ?: Caller.ANONYMOUS
        }

        @Bean
        fun refundsOnlyFromTheGateway(): CommandAuthorizer = CommandAuthorizer { caller, action ->
            if (action.type.name == "eshop-payment.payment.RefundPayment" && caller.subject != GATEWAY) {
                Authorization.Denied("Only the payment gateway refunds a payment.")
            } else {
                Authorization.Allowed
            }
        }
    }

    private companion object {
        const val GATEWAY = "payment-gateway"
        const val FORBIDDEN = 403
    }
}
