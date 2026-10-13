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
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
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
 * The test's command transport in place of Kafka, as the guide gives it, and its callers: the user the `X-User` header
 * names owns what it creates; a request without it is anonymous.
 */
@Configuration(proxyBeanMethods = false)
class BasketTestBeans {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)

    @Bean
    fun callerResolver(): CallerResolver = CallerResolver { request ->
        request.headers().firstHeader(USER)?.let { user ->
            Caller(tenantId = Caller.DEFAULT_TENANT, scope = Scope(ownerId = user), subject = user)
        }
    }

    companion object {
        const val USER = "X-User"
    }
}

/** The basket service as its `application.yaml` configures it, on random ports, with its topics in memory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasketServerTest {
    private val context = SpringApplicationBuilder(BasketServer::class.java, BasketTestBeans::class.java).run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
        "--wow.cluster.drain-timeout=1s",
        "--wow.subscription.stop-timeout=2s",
        "--wow.wait.max-timeout=15s",
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
    fun `the server should run one node of the basket context`() {
        val node = context.getBean(ServiceNode::class.java)
        node.service.assert().isEqualTo("eshop-basket-service")
        node.isReady.assert().isTrue()
        node.contexts.keys.assert().containsExactly("eshop-basket")
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

        val empty = post("/eshop-basket/basket/alice/checkout-basket", "{}", "alice")
        empty.first.assert().isEqualTo(UNPROCESSABLE)
        empty.second["code"].asString().assert().isEqualTo("eshop-basket.basket.empty")
    }

    @Test
    fun `a basket should be its buyer's alone`() {
        val other = post(
            "/eshop-basket/basket/carol/add-basket-item",
            """{"productId":"mug","unitPrice":8.50,"quantity":1}""",
            "dave"
        )
        other.first.assert().isEqualTo(FORBIDDEN)
        other.second["code"].asString().assert().isEqualTo("eshop-basket.basket.not-owner")
    }

    private fun state(id: String): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(
                uri("/eshop-basket/basket/$id/state")
            ).header(BasketTestBeans.USER, id).timeout(TIMEOUT).GET().build(),
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
                .header(BasketTestBeans.USER, user)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to json.readTree(response.body().ifBlank { "{}" })
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        const val UNPROCESSABLE = 422
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
