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

package me.ahoo.wow.it.mixed

import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Mixed-version cluster test (design WP G1): the released example server and the build under test share one MongoDB
 * and one Kafka, as during a rolling upgrade, and each must process what the other sends.
 *
 * Both nodes join the same consumer groups. Local-first is off, so a command or event reaches its processor only
 * through Kafka, and the test picks aggregate ids by partition owner (default partitioner + the group's assignment),
 * so every scenario provably crosses versions in both directions instead of depending on chance.
 *
 * It runs only when `WOW_MIXED_CURRENT_HOME` names the build under test (`example-server` `installDist`) and either
 * `WOW_MIXED_PREVIOUS_IMAGE` names the released image (CI: `ahoowang/wow-example-server:9.2.4`) or
 * `WOW_MIXED_PREVIOUS_HOME` names another `installDist` (a local dry run of the harness without pulling the image).
 * The `Mixed-Version` workflow sets them; `allIntegrationTest` skips it.
 */
@EnabledIfEnvironmentVariable(named = MixedVersionClusterTest.CURRENT_HOME_ENV, matches = ".+")
class MixedVersionClusterTest {

    @Test
    fun `a command sent to one version is processed by the other and its PROCESSED wait returns`() {
        val commandTopic = cluster.awaitTopic(CART_COMMAND)
        val commandGroup = cluster.awaitBalancedGroup(commandTopic)
        cluster.nodes.forEach { processor ->
            val sender = cluster.other(processor)
            val cartId = cluster.key("cart-processed-${sender.name}") {
                cluster.ownerOf(commandTopic, commandGroup, it) === processor
            }

            val result = sender.addCartItem(cartId, waitStage = "PROCESSED")

            result.assertSucceeded("PROCESSED")
            result["aggregateId"].asString().assert().isEqualTo(cartId)
            result["aggregateVersion"].asInt().assert().isEqualTo(1)
        }
    }

    @Test
    fun `a state event appended by one version is snapshotted by the other and the SNAPSHOT wait returns`() {
        val commandTopic = cluster.awaitTopic(CART_COMMAND)
        val stateTopic = cluster.awaitTopic(CART_STATE)
        val commandGroup = cluster.awaitBalancedGroup(commandTopic)
        val snapshotGroup = cluster.awaitBalancedGroup(stateTopic) { it.contains("snapshot", ignoreCase = true) }
        cluster.nodes.forEach { processor ->
            val snapshotter = cluster.other(processor)
            val cartId = cluster.key("cart-snapshot-${processor.name}") {
                cluster.ownerOf(commandTopic, commandGroup, it) === processor &&
                    cluster.ownerOf(stateTopic, snapshotGroup, it) === snapshotter
            }

            // Sent through the snapshotter: the command crosses versions, and its state event crosses back.
            snapshotter.addCartItem(cartId, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT")
            // Sent through the processor: the SNAPSHOT signal crosses versions to the waiting node.
            processor.addCartItem(cartId, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT")

            val states = cluster.nodes.map { it.get("/owner/$cartId/cart/state") }
            states.forEach { state ->
                state["items"].single()["quantity"].asInt().assert().isEqualTo(2)
            }
            states[0].assert().isEqualTo(states[1])
            val eventStreams = cluster.nodes.map { it.get("/cart/$cartId/event/1/100") }
            eventStreams[0].size().assert().isEqualTo(2)
            eventStreams[0].assert().isEqualTo(eventStreams[1])
        }
    }

    @Test
    fun `a saga on one version handles the events the other version appends`() {
        val orderCommandTopic = cluster.awaitTopic(ORDER_COMMAND)
        val orderEventTopic = cluster.awaitTopic(ORDER_EVENT)
        val orderCommandGroup = cluster.awaitBalancedGroup(orderCommandTopic)
        val sagaGroup = cluster.awaitBalancedGroup(orderEventTopic) { it.contains("saga", ignoreCase = true) }
        cluster.nodes.forEach { sagaNode ->
            val orderNode = cluster.other(sagaNode)
            val cartId = "cart-saga-${orderNode.name}"
            sagaNode.addCartItem(cartId, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT")
            val orderId = cluster.key("order-${orderNode.name}") {
                cluster.ownerOf(orderCommandTopic, orderCommandGroup, it) === orderNode &&
                    cluster.ownerOf(orderEventTopic, sagaGroup, it) === sagaNode
            }

            // Sent through the saga node: CreateOrder crosses to the order node, OrderCreated crosses back.
            sagaNode.command(
                method = "POST",
                path = "/tenant/$TENANT/owner/$cartId/sales-order",
                body = CREATE_ORDER_FROM_CART,
                headers = mapOf("Command-Wait-Stage" to "PROCESSED", "Command-Aggregate-Id" to orderId),
            ).assertSucceeded("PROCESSED")

            // CartSaga, on the other version, turns OrderCreated into RemoveCartItem.
            cluster.nodes.forEach { node ->
                awaitCondition("cart [$cartId] emptied by the saga, read on [${node.name}]") {
                    node.get("/owner/$cartId/cart/state")["items"].isEmpty
                }
            }
        }
    }

    @Test
    fun `with local-first on, each version filters the other's locally handled copies`() {
        newCluster(localFirst = true).use { localFirstCluster ->
            localFirstCluster.start()
            val commandTopic = localFirstCluster.awaitTopic(CART_COMMAND)
            val commandGroup = localFirstCluster.awaitBalancedGroup(commandTopic)
            localFirstCluster.nodes.forEach { sender ->
                // The command is handled where it is sent; its marked copy lands on a partition the other version owns.
                val cartId = localFirstCluster.key("cart-local-first-${sender.name}") {
                    localFirstCluster.ownerOf(commandTopic, commandGroup, it) === localFirstCluster.other(sender)
                }

                sender.addCartItem(cartId, waitStage = "PROCESSED").assertSucceeded("PROCESSED")
                sender.addCartItem(cartId, waitStage = "PROCESSED").assertSucceeded("PROCESSED")
                localFirstCluster.awaitConsumed(commandTopic, commandGroup)

                // Had the other version processed the copies too, the cart would hold more than two items' worth.
                localFirstCluster.nodes.forEach { node ->
                    node.get("/owner/$cartId/cart/state")["items"].single()["quantity"].asInt().assert().isEqualTo(2)
                    node.get("/cart/$cartId/event/1/100").size().assert().isEqualTo(2)
                }
            }
        }
    }

    private fun ExampleServerNode.addCartItem(cartId: String, waitStage: String): JsonNode =
        command(
            method = "POST",
            path = "/owner/$cartId/cart/add_cart_item",
            body = """{"productId":"$PRODUCT_ID","quantity":1}""",
            headers = mapOf("Command-Wait-Stage" to waitStage),
        )

    private fun ExampleServerNode.command(
        method: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        headers.forEach(request::header)
        return send(request.build())
    }

    private fun ExampleServerNode.get(path: String): JsonNode =
        send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build(),
        )

    private fun ExampleServerNode.send(request: HttpRequest): JsonNode {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "[$name] ${request.method()} ${request.uri()} answered ${response.statusCode()}: ${response.body()}"
        }
        return JsonSerializer.readTree(response.body())
    }

    private fun JsonNode.assertSucceeded(stage: String) {
        this["errorCode"].asString().assert().describedAs(toString()).isEqualTo("Ok")
        this["stage"].asString().assert().describedAs(toString()).isEqualTo(stage)
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + CONDITION_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) {
                return
            }
            TimeUnit.MILLISECONDS.sleep(500)
        }
        error("Timed out waiting for $description.")
    }

    companion object {
        const val CURRENT_HOME_ENV = "WOW_MIXED_CURRENT_HOME"
        private const val PREVIOUS_IMAGE_ENV = "WOW_MIXED_PREVIOUS_IMAGE"
        private const val PREVIOUS_HOME_ENV = "WOW_MIXED_PREVIOUS_HOME"
        private const val CART_COMMAND = ".cart.command"
        private const val CART_STATE = ".cart.state"
        private const val ORDER_COMMAND = ".order.command"
        private const val ORDER_EVENT = ".order.event"
        private const val TENANT = "mixed-tenant"
        private const val PRODUCT_ID = "product-1"
        private val CREATE_ORDER_FROM_CART = """
            {
              "items": [{"productId": "$PRODUCT_ID", "price": 10, "quantity": 1}],
              "address": {
                "country": "China", "province": "Shanghai", "city": "Shanghai",
                "district": "Pudong", "detail": "Road 1"
              },
              "fromCart": true
            }
        """.trimIndent()
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        private val CONDITION_TIMEOUT: Duration = Duration.ofSeconds(60)
        private val http: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        private lateinit var cluster: MixedVersionCluster

        @JvmStatic
        @BeforeAll
        fun startCluster() {
            cluster = newCluster(localFirst = false)
            cluster.start()
        }

        private fun newCluster(localFirst: Boolean): MixedVersionCluster {
            val previousImage = System.getenv(PREVIOUS_IMAGE_ENV)?.takeIf { it.isNotBlank() }
            val previousHome = System.getenv(PREVIOUS_HOME_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
            check(previousImage != null || previousHome != null) {
                "Set $PREVIOUS_IMAGE_ENV (released image) or $PREVIOUS_HOME_ENV (an installDist) for the previous node."
            }
            return MixedVersionCluster(
                previousImage = previousImage,
                previousHome = previousHome,
                currentHome = Path.of(System.getenv(CURRENT_HOME_ENV)),
                localFirst = localFirst,
            )
        }

        @JvmStatic
        @AfterAll
        fun stopCluster() {
            if (::cluster.isInitialized) {
                cluster.nodes.forEach { println("==== [${it.name}] log tail ====\n${it.logTail()}") }
                cluster.close()
            }
        }
    }
}
