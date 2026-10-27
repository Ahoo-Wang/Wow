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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.net.URI
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
    private val context = SpringApplicationBuilder(PaymentServer::class.java, InMemoryPaymentTopics::class.java).run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--eshop.payment.gateway.limit=100.00",
    )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
    }

    @Test
    fun `the server should run one node of the payment context with the ordering as its peer`() {
        val node = context.getBean(ServiceNode::class.java)
        node.service.assert().isEqualTo("eshop-payment-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-payment")
        context.getBean(Manifest::class.java).contexts.single().aggregates.map { it.name }
            .assert().containsExactly("payment")
        // The ordering's contract is read from the jar of the ordering api; the payment's own, in the payment api's jar,
        // is no peer of the node's own context (decision D234).
        val contracts = context.getBean(ContractDirectory::class.java)
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
            runBlocking { gateway.charge("p-1", BigDecimal.ONE, "k-1") }
        }
        outage.delete()
        gateway.charge("p-1", BigDecimal.ONE, "k-1").toString().assert().contains("simulated-k-1")
        PaymentServer().paymentGateway(BigDecimal.ONE, "").charge("p-2", BigDecimal.ONE, "k-2")
            .toString().assert().contains("simulated-k-2")
    }

    @Test
    fun `a payment of nothing should be invalid before the payment decides`() {
        val response = send("order-free", "0")
        response.statusCode().assert().describedAs(response.body()).isEqualTo(BAD_REQUEST)
        val problem = json.readTree(response.body())
        problem["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        problem["violations"].single().let {
            it["path"].asString().assert().isEqualTo("amount")
            it["code"].asString().assert().isEqualTo("Positive")
        }
    }

    private fun request(order: String, amount: String) {
        val response = send(order, amount)
        response.statusCode().assert().describedAs(response.body()).isEqualTo(OK)
    }

    private fun send(order: String, amount: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(uri("/eshop-payment/payment/$order/request-payment"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"orderId":"$order","amount":$amount}"""))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun state(id: String): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(uri("/eshop-payment/payment/$id/state")).timeout(TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == OK) { "The state of $id answered ${response.statusCode()}: ${response.body()}" }
        return json.readTree(response.body())["state"]
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

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
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val DEADLINE: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
