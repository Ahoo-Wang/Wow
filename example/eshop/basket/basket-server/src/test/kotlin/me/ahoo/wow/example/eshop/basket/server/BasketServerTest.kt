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
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
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
    private val context = SpringApplicationBuilder(
        BasketServer::class.java,
        BasketTestBeans::class.java
    ).profiles("dev").run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--wow.wait.max-timeout=15s",
        "--wow.peers.eshop-ordering.url=${ordering.url}",
    )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = JsonMapper.builder().build()

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
        ordering.close()
    }

    @Test
    fun `the server should run one node of the basket context`() {
        val node = context.getBean(ServiceNode::class.java)
        node.service.assert().isEqualTo("eshop-basket-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-basket")
        context.getBean(
            Manifest::class.java
        ).contexts.single().aggregates.map { it.name }.assert().containsExactly("basket")
    }

    @Test
    fun `a buyer should fill their basket and check it out into an order`() {
        post(
            "/eshop-basket/basket/alice/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":2}""",
            "alice"
        )
            .first.assert().isEqualTo(OK)
        post(
            "/eshop-basket/basket/alice/add-basket-item",
            """{"productId":"cap","unitPrice":12.00,"quantity":1}""",
            "alice"
        )
        post("/eshop-basket/basket/alice/change-basket-item-quantity", """{"productId":"cap","quantity":3}""", "alice")
            .first.assert().isEqualTo(OK)
        post(
            "/eshop-basket/basket/alice/remove-basket-item",
            """{"productId":"cap"}""",
            "alice"
        ).first.assert().isEqualTo(OK)

        val checkedOut = post("/eshop-basket/basket/alice/checkout-basket", "{}", "alice")
        checkedOut.first.assert().describedAs(checkedOut.second.toString()).isEqualTo(OK)
        checkedOut.second["results"]["orderId"].asString().assert().isEqualTo("alice-1")
        state("alice")["items"].isEmpty.assert().isTrue()
        // The saga calls the ordering service with its service credential, for the buyer: the order belongs to the
        // buyer, the call carrying the basket's scope and the buyer as its operator (decisions D163, D189, D193).
        val call = ordering.called("/eshop-ordering/order/alice-1/submit-order")
        call.service.assert().isEqualTo("eshop-basket-service")
        call.call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        call.call.operator?.id.assert().isEqualTo("alice")
        json.readTree(call.body)["items"].single()["productId"].asString().assert().isEqualTo("mug")

        val empty = post("/eshop-basket/basket/alice/checkout-basket", "{}", "alice")
        empty.first.assert().isEqualTo(UNPROCESSABLE)
        empty.second["code"].asString().assert().isEqualTo(EshopBasketContract.Basket.Errors.EMPTY)
    }

    @Test
    fun `a basket should be its buyer's alone`() {
        val other = post(
            "/eshop-basket/basket/carol/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            "dave"
        )
        other.first.assert().isEqualTo(FORBIDDEN)
        other.second["code"].asString().assert().isEqualTo(EshopBasketContract.Basket.Errors.NOT_OWNER)
    }

    @Test
    fun `an item of no quantity or a negative price should be invalid before the basket decides`() {
        val invalid = post(
            "/eshop-basket/basket/frank/add-basket-item",
            """{"productId":"mug","unitPrice":-1,"quantity":0}""",
            "frank",
        )
        invalid.first.assert().describedAs(invalid.second.toString()).isEqualTo(BAD_REQUEST)
        invalid.second["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
        invalid.second["violations"].values().map { it["path"].asString() to it["code"].asString() }
            .assert().containsExactly("quantity" to "Positive", "unitPrice" to "PositiveOrZero")
        // Nothing was decided: the basket was not created.
        read("frank", buyer("frank")).first.assert().isEqualTo(NOT_FOUND)

        post(
            "/eshop-basket/basket/frank/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            "frank",
        ).first.assert().isEqualTo(OK)
        val quantity = post(
            "/eshop-basket/basket/frank/change-basket-item-quantity",
            """{"productId":"mug","quantity":0}""",
            "frank",
        )
        quantity.first.assert().isEqualTo(BAD_REQUEST)
        quantity.second["violations"].single()["path"].asString().assert().isEqualTo("quantity")
    }

    @Test
    fun `an employee should read every buyer's basket, and the anonymous caller too`() {
        post(
            "/eshop-basket/basket/erin-buyer/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            "erin-buyer",
        ).first.assert().isEqualTo(OK)
        val employee = mapOf(USER to "erin", ROLES to "employee")
        read("erin-buyer", employee).first.assert().isEqualTo(OK)
        read("erin-buyer", emptyMap()).first.assert().isEqualTo(OK)
        // Another buyer's basket is not found for it: what a caller may not see is not found (decision D254 ④).
        read("erin-buyer", buyer("dave")).first.assert().isEqualTo(NOT_FOUND)
    }

    /** The buyer [id]: the user, and the owner of what it creates. */
    private fun buyer(id: String): Map<String, String> = mapOf(USER to id, OWNER to id)

    @Test
    fun `the server should list its routes in springdoc's document and serve Swagger UI`() {
        val docs = http.send(
            HttpRequest.newBuilder(uri("/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        docs.statusCode().assert().describedAs(docs.body()).isEqualTo(200)
        // The service's Wow routes, and the development headers Swagger UI's Authorize dialog sets.
        docs.body().assert().contains("\"/eshop-basket/", "\"${DevelopmentCallerResolver.USER}\"")
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { browser ->
            browser.send(
                HttpRequest.newBuilder(uri("/swagger-ui.html")).GET().build(),
                HttpResponse.BodyHandlers.discarding()
            )
                .statusCode().assert().isEqualTo(200)
        }
    }

    private fun read(id: String, headers: Map<String, String>): Pair<Int, String> {
        val request = HttpRequest.newBuilder(uri("/eshop-basket/basket/$id/state")).timeout(TIMEOUT).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun state(id: String): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(
                uri("/eshop-basket/basket/$id/state")
            ).header(USER, id).header(OWNER, id).timeout(TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == OK) { "The state of $id answered ${response.statusCode()}: ${response.body()}" }
        return json.readTree(response.body())["state"]
    }

    private fun post(path: String, body: String, user: String): Pair<Int, JsonNode> {
        val response = http.send(
            HttpRequest.newBuilder(uri(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header(USER, user)
                .header(OWNER, user)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val UNPROCESSABLE = 422
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
