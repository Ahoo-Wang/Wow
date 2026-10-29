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
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import java.io.File
import java.math.BigDecimal
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The test's command transport in place of Kafka, as the guide gives it: the sagas' commands to the ordering stay in
 * memory. No `@Configuration`, so the server's component scan does not pick it up where a test passes other topics.
 */
class InMemoryPaymentTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics)
}

/**
 * The payment service as its `application.yaml` configures it, on random ports, with its topics in memory and a
 * gateway limit of 100.00. No ordering runs: the test requests the payments itself, as the order-payment saga would
 * once it read an order's confirmed stock from the ordering's stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentServerTest {
    private val server = wowServer<PaymentServer>(
        "dev",
        properties = mapOf("eshop.payment.gateway.limit" to "100.00"),
        sources = listOf(InMemoryPaymentTopics::class.java),
    )

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
        request("order-approved", "29.00")
        eventually("order-approved succeeded") { state("order-approved")["status"].asString() == "SUCCEEDED" }
        state("order-approved")["transactionId"].asString().assert().startsWith("simulated-")
    }

    @Test
    fun `a payment over the gateway's limit should fail`() {
        request("order-declined", "150.00")
        eventually("order-declined failed") { state("order-declined")["status"].asString() == "FAILED" }
        state("order-declined")["reason"].asString().assert().contains("over the limit of 100.00")
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
        val answer = send("order-free", "0")
        answer.status.assert().describedAs(answer.text).isEqualTo(BAD_REQUEST)
        val problem = answer.body!!
        problem["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        problem["violations"].single().let {
            it["path"].asString().assert().isEqualTo("amount")
            it["code"].asString().assert().isEqualTo("positive")
        }
    }

    private fun request(order: String, amount: String) {
        val answer = send(order, amount)
        answer.status.assert().describedAs(answer.text).isEqualTo(OK)
    }

    @Test
    fun `the server should list its routes in springdoc's document and serve Swagger UI`() {
        val docs = server.http.get("/v3/api-docs")
        docs.status.assert().describedAs(docs.text).isEqualTo(200)
        // The service's Wow routes, and the development headers Swagger UI's Authorize dialog sets.
        docs.text.assert().contains("\"/eshop-payment/", "\"${DevelopmentCallerResolver.USER}\"")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { browser ->
            browser.send(
                HttpRequest.newBuilder(server.http.base.resolve("/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
                .statusCode().assert().isEqualTo(200)
        }
    }

    private fun send(order: String, amount: String): Answer =
        server.http.post("/eshop-payment/payment/$order/request-payment", """{"orderId":"$order","amount":$amount}""")

    private fun state(id: String): JsonNode {
        val answer = server.http.get("/eshop-payment/payment/$id/state")
        check(answer.status == OK) { "The state of $id answered $answer" }
        return answer.body!!["state"]
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in $DEADLINE: $what." }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        val DEADLINE: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
