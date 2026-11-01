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
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.OWNER
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.ROLES
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    private val server = wowServer<OrderingServer>(
        "dev",
        properties = mapOf(
            "wow.timers.poll-interval" to "200ms",
            "eshop.ordering.payment-grace-period" to "2s",
        ),
        sources = listOf(InMemoryOrderingTopics::class.java),
    )
    // endregion

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the server should run one node of the ordering context`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-ordering-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-ordering")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }.assert().containsExactly("order")
    }

    @Test
    fun `a submitted order should await validation, take its confirmations, its payment and its shipment`() {
        submit("order-paid")
        eventually("order-paid awaits validation") { status("order-paid") == "AWAITING_VALIDATION" }
        post(
            "/eshop-ordering/order/order-paid/confirm-order-stock",
            """{"productId":"mug"}"""
        ).status.assert().isEqualTo(OK)
        status("order-paid").assert().isEqualTo("AWAITING_VALIDATION")
        post(
            "/eshop-ordering/order/order-paid/confirm-order-stock",
            """{"productId":"cap"}"""
        ).status.assert().isEqualTo(OK)
        status("order-paid").assert().isEqualTo("STOCK_CONFIRMED")

        val wrongAmount = post("/eshop-ordering/order/order-paid/pay-order", """{"paymentId":"p-1","amount":1}""")
        wrongAmount.status.assert().isEqualTo(UNPROCESSABLE)
        wrongAmount.body!!["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-amount")
        post(
            "/eshop-ordering/order/order-paid/pay-order",
            """{"paymentId":"p-1","amount":29.00}"""
        ).status.assert().isEqualTo(OK)
        post("/eshop-ordering/order/order-paid/ship-order", "{}").status.assert().isEqualTo(OK)
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
        late.status.assert().isEqualTo(CONFLICT)
        late.body!!["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-status")
    }
    // endregion

    @Test
    fun `an order should be its buyer's to read, and every order an employee's`() {
        submit("order-alice", buyer("alice"))
        read("order-alice", buyer("alice")).let { answer ->
            answer.status.assert().isEqualTo(OK)
            answer.body!!["ownerId"].asString().assert().isEqualTo("alice")
        }
        // Another buyer's order is not found for it (decision D254 ④).
        read("order-alice", buyer("bob")).status.assert().isEqualTo(NOT_FOUND)
        val employee = mapOf(USER to "erin", ROLES to "employee")
        read("order-alice", employee).status.assert().isEqualTo(OK)
        // A blank user is no user: the anonymous caller, of the whole tenant.
        read("order-alice", mapOf(USER to " ")).status.assert().isEqualTo(OK)
    }

    // region numbers
    @Test
    fun `an order submitted without an id should be numbered once per request (D373, D191)`() {
        val before = LocalDate.now(ZoneOffset.UTC)
        val first = submitNew("checkout-1")
        val after = LocalDate.now(ZoneOffset.UTC)
        // SO-<yyMMdd>-<sequence>: the day it was issued, in UTC, then the sequence.
        val number = NUMBER.matchEntire(first["ref"]["id"].asString()).assert().isNotNull().actual()!!
        number.groupValues[1].assert().isIn(before.format(DAY), after.format(DAY))
        first["duplicate"].asBoolean().assert().isFalse()

        // The same request again, as a retried or replayed checkout sends it: the same order and number, no other.
        val retried = submitNew("checkout-1")
        retried["ref"]["id"].asString().assert().isEqualTo(number.value)
        retried["duplicate"].asBoolean().assert().isTrue()

        // Another request is another order, with a later number.
        val next = NUMBER.matchEntire(submitNew("checkout-2")["ref"]["id"].asString()).assert().isNotNull().actual()!!
        next.groupValues[2].toLong().assert().isGreaterThan(number.groupValues[2].toLong())
        // The number is the order's id: the order is read by it.
        read(number.value, buyer("alice-numbers")).status.assert().isEqualTo(OK)
    }

    /** Submits an order that names no id, as the basket's checkout does, with the request id [requestId]. */
    private fun submitNew(requestId: String): JsonNode {
        val submitted = post(
            "/eshop-ordering/order/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
            buyer("alice-numbers") + (WowHeaders.REQUEST_ID to requestId),
        )
        submitted.status.assert().describedAs(submitted.text).isEqualTo(OK)
        return submitted.body!!
    }
    // endregion

    @Test
    fun `an order without items, or with an invalid one, should be invalid before the order decides`() {
        val empty = post("/eshop-ordering/order/order-empty/submit-order", """{"items":[]}""")
        empty.status.assert().describedAs(empty.text).isEqualTo(BAD_REQUEST)
        empty.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        empty.body!!["violations"].single()["path"].asString().assert().isEqualTo("items")
        empty.body!!["violations"].single()["code"].asString().assert().isEqualTo("not-empty")

        val invalid = post(
            "/eshop-ordering/order/order-invalid/submit-order",
            """{"items":[{"productId":"mug","unitPrice":-1,"quantity":0}]}""",
        )
        invalid.status.assert().isEqualTo(BAD_REQUEST)
        invalid.body!!["violations"].values().map { it["path"].asString() to it["code"].asString() }
            .assert().containsExactly("items[0].quantity" to "positive", "items[0].unitPrice" to "positive-or-zero")
        read("order-invalid").status.assert().isEqualTo(NOT_FOUND)
    }

    @Test
    fun `the server should list its routes in springdoc's document and serve Swagger UI`() {
        val docs = server.http.get("/v3/api-docs")
        docs.status.assert().describedAs(docs.text).isEqualTo(200)
        // The service's Wow routes, and the development headers Swagger UI's Authorize dialog sets.
        docs.text.assert().contains("\"/eshop-ordering/", "\"${DevelopmentCallerResolver.USER}\"")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { browser ->
            browser.send(
                HttpRequest.newBuilder(server.http.base.resolve("/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
                .statusCode().assert().isEqualTo(200)
        }
    }

    /** The buyer [id]: the user, and the owner of what it creates. */
    @Test
    fun `only an employee should ship a paid order, not its buyer (UI-49)`() {
        val alice = buyer("alice-ships")
        submit("order-shipped", alice)
        eventually("order-shipped awaits validation") { status("order-shipped") == "AWAITING_VALIDATION" }
        post("/eshop-ordering/order/order-shipped/confirm-order-stock", """{"productId":"mug"}""", alice)
            .status.assert().isEqualTo(OK)
        post("/eshop-ordering/order/order-shipped/confirm-order-stock", """{"productId":"cap"}""", alice)
            .status.assert().isEqualTo(OK)
        post("/eshop-ordering/order/order-shipped/pay-order", """{"paymentId":"p-2","amount":29.00}""", alice)
            .status.assert().isEqualTo(OK)
        val refused = post("/eshop-ordering/order/order-shipped/ship-order", "{}", alice)
        refused.status.assert().describedAs(refused.text).isEqualTo(FORBIDDEN)
        refused.body!!["code"].asString().assert().isEqualTo("wow.auth.forbidden")
        refused.body!!["detail"].asString().assert().contains("Only an employee ships an order")
        status("order-shipped").assert().isEqualTo("PAID")
        post("/eshop-ordering/order/order-shipped/ship-order", "{}", mapOf(USER to "erin", ROLES to "employee"))
            .status.assert().isEqualTo(OK)
        status("order-shipped").assert().isEqualTo("SHIPPED")
    }

    @Test
    fun `the snapshot rebuild should be an employee's alone (UI-50)`() {
        val rebuild = "/eshop-ordering/order/wow.snapshot.rebuild"
        for (caller in listOf(emptyMap(), buyer("alice-rebuilds"))) {
            val refused = post(rebuild, """{"ids":["order-1"]}""", caller)
            refused.status.assert().describedAs(refused.text).isEqualTo(FORBIDDEN)
            refused.body!!["detail"].asString().assert().contains("Only an employee sends wow.snapshot.Rebuild")
        }
        post(rebuild, """{"ids":["order-1"]}""", mapOf(USER to "erin", ROLES to "employee"))
            .status.assert().isEqualTo(OK)
    }

    private fun buyer(id: String): Map<String, String> = mapOf(USER to id, OWNER to id)

    private fun submit(id: String, headers: Map<String, String> = emptyMap()) {
        val submitted = post(
            "/eshop-ordering/order/$id/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2},""" +
                """{"productId":"cap","unitPrice":12.00,"quantity":1}]}""",
            headers,
        )
        submitted.status.assert().describedAs(submitted.text).isEqualTo(OK)
    }

    private fun read(id: String, headers: Map<String, String> = emptyMap()): Answer =
        server.http.get("/eshop-ordering/order/$id/state", headers)

    private fun state(id: String): JsonNode {
        val answer = read(id)
        check(answer.status == OK) { "The state of $id answered $answer" }
        return answer.body!!["state"]
    }

    private fun status(id: String): String = state(id)["status"].asString()

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Answer =
        server.http.post(path, body, headers)

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in $DEADLINE: $what." }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
        val DEADLINE: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(100)
        val GRACE_AND_POLL: Duration = Duration.ofMillis(3_000)

        /** An order's number: `SO-`, the day it was issued (`yyMMdd`), `-`, its sequence. */
        val NUMBER = Regex("""SO-(\d{6})-(\d+)""")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
    }
}
