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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
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

    /**
     * Local-first on both versions: a command, its state events and the events a saga reacts to are processed on the
     * node that sent them, and the other version must filter their `local_first` copies, not process them a second
     * time (which the request-ID check would only partly hide). Each node's own `wow.operation` counters tell who
     * processed what; both the released 9.2.x (9.2.4 in CI) and the current build meter handlers there.
     */
    @Test
    fun `with local-first on, each version filters the other's locally handled copies`() {
        newCluster(localFirst = true).use { localFirstCluster ->
            localFirstCluster.start()
            val commandTopic = localFirstCluster.awaitTopic(CART_COMMAND)
            val stateTopic = localFirstCluster.awaitTopic(CART_STATE)
            val orderCommandTopic = localFirstCluster.awaitTopic(ORDER_COMMAND)
            val orderEventTopic = localFirstCluster.awaitTopic(ORDER_EVENT)
            val commandGroup = localFirstCluster.awaitBalancedGroup(commandTopic)
            val snapshotGroup = localFirstCluster.awaitBalancedGroup(stateTopic) {
                it.contains("snapshot", ignoreCase = true)
            }
            val orderCommandGroup = localFirstCluster.awaitBalancedGroup(orderCommandTopic)
            val sagaGroup = localFirstCluster.awaitBalancedGroup(orderEventTopic) { it.contains("saga", ignoreCase = true) }
            localFirstCluster.nodes.forEach { sender ->
                val other = localFirstCluster.other(sender)
                // Every copy lands on a partition the other version owns.
                val cartId = localFirstCluster.key("cart-local-first-${sender.name}") {
                    localFirstCluster.ownerOf(commandTopic, commandGroup, it) === other &&
                        localFirstCluster.ownerOf(stateTopic, snapshotGroup, it) === other
                }
                val orderId = localFirstCluster.key("order-local-first-${sender.name}") {
                    localFirstCluster.ownerOf(orderCommandTopic, orderCommandGroup, it) === other &&
                        localFirstCluster.ownerOf(orderEventTopic, sagaGroup, it) === other
                }
                val before = localFirstCluster.nodes.associateWith { it.handled() }

                sender.addCartItem(cartId, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT")
                sender.addCartItem(cartId, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT")
                sender.command(
                    method = "POST",
                    path = "/tenant/$TENANT/owner/$cartId/sales-order",
                    body = CREATE_ORDER_FROM_CART,
                    headers = mapOf("Command-Wait-Stage" to "PROCESSED", "Command-Aggregate-Id" to orderId),
                ).assertSucceeded("PROCESSED")
                awaitCondition("cart [$cartId] emptied by the saga on [${sender.name}]") {
                    sender.get("/owner/$cartId/cart/state")["items"].isEmpty
                }
                listOf(commandTopic, stateTopic, orderCommandTopic, orderEventTopic).forEach { topic ->
                    localFirstCluster.groupsOf(topic).forEach { localFirstCluster.awaitConsumed(topic, it.groupId()) }
                }

                val senderHandled = sender.handled() - before.getValue(sender)
                val otherHandled = other.handled() - before.getValue(other)
                // Two AddCartItem, the saga's RemoveCartItem; their snapshots; CreateOrder; the saga on OrderCreated.
                senderHandled.cartCommands.assert().isEqualTo(3.0)
                senderHandled.cartSnapshots.assert().isEqualTo(3.0)
                senderHandled.orderCommands.assert().isEqualTo(1.0)
                senderHandled.sagas.assert().isGreaterThanOrEqualTo(1.0)
                otherHandled.assert().describedAs("[${other.name}] processed locally handled copies").isEqualTo(Handled())
                localFirstCluster.nodes.forEach { node ->
                    node.get("/cart/$cartId/event/1/100").size().assert().isEqualTo(3)
                }
            }
        }
    }

    /**
     * Rolling upgrade (design X7): the build under test consumes each bounded context with one consumer per dispatcher
     * (all of the context's topics), the released node with one consumer per aggregate topic, in the same consumer
     * groups. In two phases, one node is stopped (SIGTERM: graceful shutdown with commands in flight, the member leaves
     * every group) and started again while commands and saga-driven orders flow through the other node: first the node
     * under test restarts (the released node takes over and gives back), then the released node (the node under test
     * takes over all partitions with its per-context consumers). Each phase's carts are chosen so both nodes own some of
     * them before the restart, so both versions process commands.
     *
     * Every command must take effect exactly once: the event store's request-ID check turns a redelivery
     * (at-least-once) into a duplicate-free result, so each cart's quantity and event count equal the commands accepted.
     * Every order created through `CartSaga` (an event processor group crossing versions) must empty its cart, and the
     * snapshot dispatchers must catch up to the last version.
     */
    @Test
    @Order(Int.MAX_VALUE)
    fun `a rolling restart across per-aggregate and per-context consumers loses and duplicates no command`() {
        val cartCommandTopic = cluster.awaitTopic(CART_COMMAND)
        val orderCommandTopic = cluster.awaitTopic(ORDER_COMMAND)
        val orderEventTopic = cluster.awaitTopic(ORDER_EVENT)
        val commandGroup = cluster.awaitBalancedGroup(cartCommandTopic)
        cluster.awaitBalancedGroup(orderCommandTopic)
        val sagaGroup = cluster.awaitBalancedGroup(orderEventTopic) { it.contains("saga", ignoreCase = true) }
        assertPerContextMembership(commandGroup, cartCommandTopic, orderCommandTopic)
        assertPerContextMembership(sagaGroup, orderEventTopic)

        rollingPhase(restarted = cluster.current, sender = cluster.previous, commandGroup, cartCommandTopic, "a")
        rollingPhase(restarted = cluster.previous, sender = cluster.current, commandGroup, cartCommandTopic, "b")
    }

    /** The node under test runs one consumer per bounded context: the one owning [topics] owns all of them. */
    private fun assertPerContextMembership(group: String, vararg topics: String) {
        val members = cluster.memberTopics(group, cluster.current.name)
        members.single { topics.first() in it }.assert()
            .describedAs("members of [${cluster.current.name}] in $group: $members")
            .contains(*topics)
        println("[$group] ${cluster.previous.name}: ${cluster.memberTopics(group, cluster.previous.name)}")
        println("[$group] ${cluster.current.name}: $members")
    }

    private fun rollingPhase(
        restarted: ExampleServerNode,
        sender: ExampleServerNode,
        commandGroup: String,
        cartCommandTopic: String,
        phase: String,
    ) {
        cluster.awaitBalancedGroup(cartCommandTopic)
        // Half of the carts are owned by each node before the restart, so both versions process commands.
        val carts = cluster.nodes.flatMap { owner ->
            (0 until ROLLING_CARTS / 2).map { index ->
                cluster.key("cart-rolling-$phase-${owner.name}-$index") {
                    cluster.ownerOf(cartCommandTopic, commandGroup, it) === owner
                }
            }
        }
        val sagaCarts = (0 until SAGA_CARTS).map { "cart-rolling-saga-$phase-$it" }
        sagaCarts.forEach { sender.addCartItem(it, waitStage = "SNAPSHOT").assertSucceeded("SNAPSHOT") }

        val accepted = ConcurrentHashMap<String, AtomicInteger>()
        val senders = Executors.newFixedThreadPool(2)
        try {
            val commands = senders.submit {
                repeat(ROLLING_ROUNDS) {
                    carts.forEach { cartId ->
                        sender.addCartItem(cartId, waitStage = "SENT").assertSucceeded("SENT")
                        accepted.computeIfAbsent(cartId) { AtomicInteger() }.incrementAndGet()
                    }
                }
            }
            val orders = senders.submit {
                sagaCarts.forEach { cartId ->
                    sender.command(
                        method = "POST",
                        path = "/tenant/$TENANT/owner/$cartId/sales-order",
                        body = CREATE_ORDER_FROM_CART,
                        headers = mapOf("Command-Wait-Stage" to "SENT"),
                    ).assertSucceeded("SENT")
                }
            }
            awaitCondition("the first commands of phase [$phase] sent") {
                accepted.values.sumOf { it.get() } >= carts.size * 2
            }
            // SIGTERM with commands in flight: graceful shutdown, the node's members leave every group.
            restarted.close()
            awaitCondition("[${restarted.name}] left $commandGroup") {
                cluster.memberTopics(commandGroup, restarted.name).isEmpty()
            }
            restarted.start()
            commands.get(SENDING_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            orders.get(SENDING_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
        } finally {
            senders.shutdownNow()
        }
        accepted.values.sumOf { it.get() }.assert().isEqualTo(carts.size * ROLLING_ROUNDS)

        carts.forEach { cartId ->
            val expected = accepted.getValue(cartId).get()
            awaitCondition("cart [$cartId] has $expected items") {
                sender.get("/owner/$cartId/cart/state")["items"].single()["quantity"].asInt() >= expected
            }
            cluster.nodes.forEach { node ->
                node.get("/owner/$cartId/cart/state")["items"].single()["quantity"].asInt()
                    .assert().describedAs("quantity of [$cartId] on [${node.name}]").isEqualTo(expected)
            }
            sender.get("/cart/$cartId/event/1/1000").size()
                .assert().describedAs("event streams of [$cartId]").isEqualTo(expected)
            awaitCondition("snapshot of [$cartId] at version $expected") {
                restarted.get("/owner/$cartId/cart/snapshot")["version"].asInt() == expected
            }
        }
        sagaCarts.forEach { cartId ->
            awaitCondition("cart [$cartId] emptied by CartSaga after phase [$phase]") {
                cluster.nodes.all { node -> node.get("/owner/$cartId/cart/state")["items"].isEmpty }
            }
            // AddCartItem, then exactly one RemoveCartItem from the saga.
            sender.get("/cart/$cartId/event/1/1000").size()
                .assert().describedAs("event streams of saga cart [$cartId]").isEqualTo(2)
        }
    }

    /** What a node's handlers processed, from its own `wow.operation` timers. */
    private data class Handled(
        val cartCommands: Double = 0.0,
        val cartSnapshots: Double = 0.0,
        val orderCommands: Double = 0.0,
        val sagas: Double = 0.0,
    ) {
        operator fun minus(other: Handled) = Handled(
            cartCommands - other.cartCommands,
            cartSnapshots - other.cartSnapshots,
            orderCommands - other.orderCommands,
            sagas - other.sagas,
        )
    }

    private fun ExampleServerNode.handled() = Handled(
        cartCommands = operations("command_handler", "cart"),
        cartSnapshots = operations("snapshot_handler", "cart"),
        orderCommands = operations("command_handler", "order"),
        sagas = operations("stateless_saga_handler", "order"),
    )

    /** The `wow.operation` count of [component] on [aggregate], `0` while no such operation ran. */
    private fun ExampleServerNode.operations(component: String, aggregate: String): Double {
        val request = HttpRequest.newBuilder(
            URI.create("$baseUrl/actuator/metrics/wow.operation?tag=component:$component&tag=aggregate:$aggregate"),
        ).timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) {
            return 0.0
        }
        check(response.statusCode() == 200) {
            "[$name] ${request.uri()} answered ${response.statusCode()}: ${response.body()}"
        }
        return JsonSerializer.readTree(response.body())["measurements"]
            .first { it["statistic"].asString() == "COUNT" }["value"].asDouble()
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
        private const val ROLLING_CARTS = 10
        private const val ROLLING_ROUNDS = 20
        private const val SAGA_CARTS = 5
        private val SENDING_TIMEOUT: Duration = Duration.ofMinutes(5)
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
