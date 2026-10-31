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

package me.ahoo.wow.example.eshop.basket.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.example.eshop.basket.api.basket.EshopBasketContract
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.json.WowJson
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

/**
 * The test's command transport in place of Kafka, as the guide gives it. The callers are the host's in development
 * ([DevelopmentCallerResolver]): a buyer names itself as the user and the owner, and owns what it creates.
 */
@Configuration(proxyBeanMethods = false)
class BasketTestBeans {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics.of(topics, producer = topics)
}

/**
 * The basket service as its `application.yaml` configures it, on random ports, with its topics in memory, and the
 * ordering service's command gateway a fake that records the checkout saga's calls (decision D193).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasketServerTest {
    private val ordering = FakeOrdering()
    private val server = wowServer<BasketServer>(
        "dev",
        properties = mapOf("wow.peers.eshop-ordering.url" to ordering.url),
        sources = listOf(BasketTestBeans::class.java),
    )

    @AfterAll
    fun close() {
        server.close()
        ordering.close()
    }

    @Test
    fun `the server should run one node of the basket context`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-basket-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-basket")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }.assert().containsExactly("basket")
    }

    @Test
    fun `a buyer should fill their basket and check it out into an order`() {
        // The buyer's routes name no basket: its basket is the one whose id is the buyer's.
        mine("add-basket-item", """{"productId":"mug","unitPrice":8.50,"quantity":2}""", "alice")
            .status.assert().isEqualTo(OK)
        mine("add-basket-item", """{"productId":"cap","unitPrice":12.00,"quantity":1}""", "alice")
        mine("change-basket-item-quantity", """{"productId":"cap","quantity":3}""", "alice")
            .status.assert().isEqualTo(OK)
        mine("remove-basket-item", """{"productId":"cap"}""", "alice").status.assert().isEqualTo(OK)

        val checkedOut = mine("checkout-basket", "{}", "alice")
        checkedOut.status.assert().describedAs(checkedOut.text).isEqualTo(OK)
        state("alice")["items"].isEmpty.assert().isTrue()
        // The saga calls the ordering service's internal port, naming itself, for the buyer: the order belongs to the
        // buyer, the call carrying the basket's scope and the buyer as its operator (decisions D163, D189, D193). It
        // names no order: the ordering numbers the order it creates (decision D373).
        val call = ordering.called("/eshop-ordering/order/submit-order")
        call.service.assert().isEqualTo("eshop-basket-service")
        call.call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        call.call.operator?.id.assert().isEqualTo("alice")
        WowJson.mapper.readTree(call.body)["items"].single()["productId"].asString().assert().isEqualTo("mug")

        val empty = mine("checkout-basket", "{}", "alice")
        empty.status.assert().isEqualTo(UNPROCESSABLE)
        empty.body!!["code"].asString().assert().isEqualTo(EshopBasketContract.Basket.Errors.EMPTY)
    }

    @Test
    fun `a basket should be its buyer's alone`() {
        val other = server.http.post(
            "/eshop-basket/basket/carol/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            buyer("dave"),
        )
        other.status.assert().describedAs(other.text).isEqualTo(FORBIDDEN)
        other.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.AGGREGATE_FORBIDDEN)
        // By its own id, the buyer reaches the basket the routes without one do.
        server.http.post(
            "/eshop-basket/basket/dave/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            buyer("dave"),
        ).status.assert().isEqualTo(OK)
        state("dave")["items"].single()["productId"].asString().assert().isEqualTo("mug")
    }

    @Test
    fun `an item of no quantity or a negative price should be invalid before the basket decides`() {
        val invalid = mine("add-basket-item", """{"productId":"mug","unitPrice":-1,"quantity":0}""", "frank")
        invalid.status.assert().describedAs(invalid.text).isEqualTo(BAD_REQUEST)
        invalid.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        invalid.body!!["violations"].values().map { it["path"].asString() to it["code"].asString() }
            .assert().containsExactly("quantity" to "positive", "unitPrice" to "positive-or-zero")
        // Nothing was decided: the basket was not created.
        readMine(buyer("frank")).status.assert().isEqualTo(NOT_FOUND)

        mine("add-basket-item", """{"productId":"mug","unitPrice":8.50,"quantity":1}""", "frank")
            .status.assert().isEqualTo(OK)
        val quantity = mine("change-basket-item-quantity", """{"productId":"mug","quantity":0}""", "frank")
        quantity.status.assert().isEqualTo(BAD_REQUEST)
        quantity.body!!["violations"].single()["path"].asString().assert().isEqualTo("quantity")
    }

    @Test
    fun `an employee should read every buyer's basket by its id, and the anonymous caller too`() {
        mine("add-basket-item", """{"productId":"mug","unitPrice":8.50,"quantity":1}""", "erin-buyer")
            .status.assert().isEqualTo(OK)
        val employee = mapOf(USER to "erin", ROLES to "employee")
        read("erin-buyer", employee).status.assert().isEqualTo(OK)
        read("erin-buyer", emptyMap()).status.assert().isEqualTo(OK)
        // Another buyer's basket is not found for it: what a caller may not see is not found (decision D254 ④).
        read("erin-buyer", buyer("dave")).status.assert().isEqualTo(NOT_FOUND)
        // An employee has no basket of its own: the route without an id is not for it.
        val noOwner = readMine(employee)
        noOwner.status.assert().describedAs(noOwner.text).isEqualTo(BAD_REQUEST)
        noOwner.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_MALFORMED)
    }

    /** The buyer [id]: the user, and the owner of what it creates. */
    private fun buyer(id: String): Map<String, String> = mapOf(USER to id, OWNER to id)

    @Test
    fun `the server should list its routes in springdoc's document and serve Swagger UI`() {
        val docs = server.http.get("/v3/api-docs")
        docs.status.assert().describedAs(docs.text).isEqualTo(200)
        // The service's Wow routes, and the development headers Swagger UI's Authorize dialog sets.
        docs.text.assert().contains("\"/eshop-basket/", "\"${DevelopmentCallerResolver.USER}\"")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { browser ->
            browser.send(
                HttpRequest.newBuilder(server.http.base.resolve("/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
                .statusCode().assert().isEqualTo(200)
        }
    }

    private fun read(id: String, headers: Map<String, String>): Answer =
        server.http.get("/eshop-basket/basket/$id/state", headers)

    /** The caller's own basket, by the read route that names none. */
    private fun readMine(headers: Map<String, String>): Answer = server.http.get("/eshop-basket/basket/state", headers)

    /** The buyer [id]'s basket, by the route without an id. */
    private fun state(id: String): JsonNode {
        val answer = readMine(buyer(id))
        check(answer.status == OK) { "The state of $id answered $answer" }
        return answer.body!!["state"]
    }

    /** The command [segment] on the buyer [user]'s own basket, by the route that names none. */
    private fun mine(segment: String, body: String, user: String): Answer =
        server.http.post("/eshop-basket/basket/$segment", body, buyer(user))

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
    }
}
