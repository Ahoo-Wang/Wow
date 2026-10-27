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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The test's command transport in place of Kafka, as the guide gives it: the sagas' commands to the inventory stay in memory. */
// region topics
@Configuration(proxyBeanMethods = false)
class InMemoryOrderingTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics)
}
// endregion

/**
 * The ordering service as its `application.yaml` configures it, on random ports, with its topics in memory and a
 * payment grace period of two seconds. No inventory runs: the test confirms the order's products itself, as the
 * stock-confirmation saga would once it read the reservations from the inventory's stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingServerTest {
    // region start
    private val context = SpringApplicationBuilder(
        OrderingServer::class.java,
        InMemoryOrderingTopics::class.java
    ).profiles("dev").run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--wow.wait.max-timeout=15s",
        "--wow.timers.poll-interval=200ms",
        "--eshop.ordering.payment-grace-period=2s",
    )
    // endregion

    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
    }

    @Test
    fun `the server should run one node of the ordering context`() {
        val node = context.getBean(ServiceNode::class.java)
        node.service.assert().isEqualTo("eshop-ordering-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-ordering")
        context.getBean(
            Manifest::class.java
        ).contexts.single().aggregates.map { it.name }.assert().containsExactly("order")
    }

    @Test
    fun `a submitted order should await validation, take its confirmations, its payment and its shipment`() {
        submit("order-paid")
        eventually("order-paid awaits validation") { status("order-paid") == "AWAITING_VALIDATION" }
        post(
            "/eshop-ordering/order/order-paid/confirm-order-stock",
            """{"productId":"mug"}"""
        ).first.assert().isEqualTo(OK)
        status("order-paid").assert().isEqualTo("AWAITING_VALIDATION")
        post(
            "/eshop-ordering/order/order-paid/confirm-order-stock",
            """{"productId":"cap"}"""
        ).first.assert().isEqualTo(OK)
        status("order-paid").assert().isEqualTo("STOCK_CONFIRMED")

        val wrongAmount = post("/eshop-ordering/order/order-paid/pay-order", """{"paymentId":"p-1","amount":1}""")
        wrongAmount.first.assert().isEqualTo(UNPROCESSABLE)
        wrongAmount.second["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-amount")
        post(
            "/eshop-ordering/order/order-paid/pay-order",
            """{"paymentId":"p-1","amount":29.00}"""
        ).first.assert().isEqualTo(OK)
        post("/eshop-ordering/order/order-paid/ship-order", "{}").first.assert().isEqualTo(OK)
        status("order-paid").assert().isEqualTo("SHIPPED")
        // The grace period has long passed, and the paid order kept its status.
        Thread.sleep(GRACE_AND_POLL.toMillis())
        status("order-paid").assert().isEqualTo("SHIPPED")
    }

    // region grace
    @Test
    fun `an order not paid within its grace period should cancel itself`() {
        submit("order-unpaid")
        eventually("order-unpaid awaits validation") { status("order-unpaid") == "AWAITING_VALIDATION" }
        post("/eshop-ordering/order/order-unpaid/confirm-order-stock", """{"productId":"mug"}""")
        post("/eshop-ordering/order/order-unpaid/confirm-order-stock", """{"productId":"cap"}""")
        status("order-unpaid").assert().isEqualTo("STOCK_CONFIRMED")
        eventually("order-unpaid cancelled") { status("order-unpaid") == "CANCELLED" }
        state("order-unpaid")["cancelReason"].asString().assert().contains("payment grace period")
        val late = post("/eshop-ordering/order/order-unpaid/pay-order", """{"paymentId":"p-2","amount":29.00}""")
        late.first.assert().isEqualTo(CONFLICT)
        late.second["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-status")
    }
    // endregion

    @Test
    fun `an order should be its buyer's to read, and every order an employee's`() {
        submit("order-alice", buyer("alice"))
        read("order-alice", buyer("alice")).let { (status, view) ->
            status.assert().isEqualTo(OK)
            view["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        }
        // Another buyer's order is not found for it (decision D254 ④).
        read("order-alice", buyer("bob")).first.assert().isEqualTo(NOT_FOUND)
        val employee = mapOf(USER to "erin", ROLES to "employee")
        read("order-alice", employee).first.assert().isEqualTo(OK)
        // A blank user is no user: the anonymous caller, of the whole tenant.
        read("order-alice", mapOf(USER to " ")).first.assert().isEqualTo(OK)
    }

    @Test
    fun `an order without items, or with an invalid one, should be invalid before the order decides`() {
        val empty = post("/eshop-ordering/order/order-empty/submit-order", """{"items":[]}""")
        empty.first.assert().describedAs(empty.second.toString()).isEqualTo(BAD_REQUEST)
        empty.second["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        empty.second["violations"].single()["path"].asString().assert().isEqualTo("items")
        empty.second["violations"].single()["code"].asString().assert().isEqualTo("NotEmpty")

        val invalid = post(
            "/eshop-ordering/order/order-invalid/submit-order",
            """{"items":[{"productId":"mug","unitPrice":-1,"quantity":0}]}""",
        )
        invalid.first.assert().isEqualTo(BAD_REQUEST)
        invalid.second["violations"].values().map { it["path"].asString() to it["code"].asString() }
            .assert().containsExactly("items[0].quantity" to "Positive", "items[0].unitPrice" to "PositiveOrZero")
        read("order-invalid").first.assert().isEqualTo(NOT_FOUND)
    }

    /** The buyer [id]: the user, and the owner of what it creates. */
    private fun buyer(id: String): Map<String, String> = mapOf(USER to id, OWNER to id)

    private fun submit(id: String, headers: Map<String, String> = emptyMap()) {
        val submitted = post(
            "/eshop-ordering/order/$id/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2},""" +
                """{"productId":"cap","unitPrice":12.00,"quantity":1}]}""",
            headers,
        )
        submitted.first.assert().describedAs(submitted.second.toString()).isEqualTo(OK)
    }

    private fun read(id: String, headers: Map<String, String> = emptyMap()): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri("/eshop-ordering/order/$id/state")).timeout(TIMEOUT).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private fun state(id: String): JsonNode {
        val (status, view) = read(id)
        check(status == OK) { "The state of $id answered $status: $view" }
        return view["state"]
    }

    private fun status(id: String): String = state(id)["status"].asString()

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, JsonNode> {
        val request = HttpRequest.newBuilder(uri(path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
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
        const val CONFLICT = 409
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val DEADLINE: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(100)
        val GRACE_AND_POLL: Duration = Duration.ofMillis(3_000)
    }
}
