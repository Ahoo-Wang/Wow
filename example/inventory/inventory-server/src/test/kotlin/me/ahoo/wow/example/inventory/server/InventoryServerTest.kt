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

package me.ahoo.wow.example.inventory.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.example.inventory.domain.stock.StockRejections
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.transport.EnvelopeCodec
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
import kotlin.time.Duration.Companion.seconds

/**
 * The topics of the test, in memory: the inventory's command topic, the shop's receipts and its order stream. No
 * `@Configuration`, so the server's component scan does not pick it up where a test does not pass it.
 */
class InMemoryTransport {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(InventoryServerTest.PARTITIONS)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics, intake = true)
}

/**
 * The inventory server in this JVM, its topics in memory in place of Kafka: it takes the shop's commands from its
 * command topic, sends the receipt of one it rejects back to the shop, and settles the reservations of the orders it
 * reads from the shop's order stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryServerTest {
    private val context: ConfigurableApplicationContext =
        SpringApplicationBuilder(InventoryServer::class.java, InMemoryTransport::class.java).run(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
            "--wow.log.partitions=$PARTITIONS",
            "--wow.subscription.stop-timeout=2s",
        )
    private val topics = context.getBean(InMemoryTopics::class.java)
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterAll
    fun close() {
        context.close()
        http.shutdownNow()
    }

    private fun send(request: HttpRequest.Builder): Pair<Int, JsonNode> {
        val response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body())
    }

    private fun post(path: String, body: String) = send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)),
    )

    private fun stock(product: String): JsonNode =
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/inventory/stock/$product/state")).GET()).second

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in $DEADLINE." }
            Thread.sleep(POLL.toMillis())
        }
    }

    private fun reserve(id: String, product: String, order: String, quantity: Int, replyTo: ReplyTo? = null) = runBlocking {
        val ref = STOCK.ref(TENANT, product)
        val wire = CommandWire(
            type = "inventory.stock.ReserveStock",
            id = id,
            requestId = id,
            ref = ref,
            body = json.readTree("""{"orderId":"$order","quantity":$quantity}"""),
            causation = Causation("place-$order", "example.order/$TENANT/$order@1"),
            replyTo = replyTo,
        )
        topics.send(COMMANDS, listOf(CommandMessages.command(wire, Partitioner.partitionOf(ref, PARTITIONS))))
    }

    @Test
    fun `a reservation sent to the command topic should be decided once, however often it is delivered`() {
        post("/inventory/stock/apple/receive-stock", """{"quantity":10}""").first.assert().isEqualTo(200)
        reserve("reserve-1", "apple", "order-1", 3)
        // A copy of the message, as a redelivery: the kernel decides its request once.
        reserve("reserve-1", "apple", "order-1", 3)
        reserve("reserve-2", "apple", "order-2", 2)
        eventually { stock("apple")["state"]["reservations"].has("order-2") }
        val state = stock("apple")
        state["version"].asInt().assert().isEqualTo(3)
        state["state"]["available"].asInt().assert().isEqualTo(5)
        state["state"]["reservations"]["order-1"].asInt().assert().isEqualTo(3)
    }

    @Test
    fun `a reservation the stock cannot cover should send its receipt to the saga that asked`(): Unit = runBlocking {
        post("/inventory/stock/durian/receive-stock", """{"quantity":1}""").first.assert().isEqualTo(200)
        val order = AggregateType("example", "order").ref(TENANT, "order-3")
        reserve("reserve-3", "durian", "order-3", 2, ReplyTo("example", "OrderSaga.onOrderCreated", order, 1))
        val receipt = withTimeout(30.seconds) {
            var written: Int? = null
            while (written == null) {
                written = topics.partitions(RECEIPTS).firstOrNull { topics.offsets(RECEIPTS, it).end > 0 }
                delay(POLL.toMillis())
            }
            topics.read(RECEIPTS, written, 0).first()
        }
        val rejected = CommandMessages.rejectedOf(EnvelopeCodec.decode(receipt.value))
        rejected.commandId.assert().isEqualTo("reserve-3")
        rejected.error.code.assert().isEqualTo(StockRejections.INSUFFICIENT)
        rejected.executionId.ref.assert().isEqualTo(order)
        stock("durian")["state"]["available"].asInt().assert().isEqualTo(1)
    }

    @Test
    fun `an order shipped or cancelled on the shop's stream should commit or release its reservations`() {
        post("/inventory/stock/pear/receive-stock", """{"quantity":10}""").first.assert().isEqualTo(200)
        reserve("reserve-4", "pear", "order-4", 4)
        reserve("reserve-5", "pear", "order-5", 1)
        eventually { stock("pear")["state"]["reservations"].size() == 2 }
        publish(AggregateType("example", "order").ref(TENANT, "order-4"), "example.order.OrderShipped", 4)
        publish(AggregateType("example", "order").ref(TENANT, "order-5"), "example.order.OrderCancelled", 1)
        eventually { stock("pear")["state"]["reservations"].isEmpty }
        val state = stock("pear")["state"]
        state["onHand"].asInt().assert().isEqualTo(6)
        state["available"].asInt().assert().isEqualTo(6)
    }

    private fun publish(order: AggregateRef, event: String, quantity: Int) = runBlocking {
        val view = json.readTree("""{"items":[{"productId":"pear","quantity":$quantity}]}""")
        val record = StateEventRecord(
            ref = order,
            version = 3,
            commandId = "command-${order.id}",
            requestId = "request-${order.id}",
            commandType = "example.order.Command",
            commitTime = System.currentTimeMillis(),
            events = listOf(StateEventPayload(event, 1, json.createObjectNode())),
            state = StateView(3, "fingerprint", view),
        )
        topics.send(ORDERS, listOf(EnvelopeCodec.message(record)))
    }

    companion object {
        const val PARTITIONS = 4
        private const val TENANT = "(0)"
        private const val COMMANDS = "wow.inventory.commands"
        private const val RECEIPTS = "wow.example.receipts"
        private const val ORDERS = "wow.example.order.state"
        private val STOCK = AggregateType("inventory", "stock")
        private val DEADLINE: Duration = Duration.ofSeconds(30)
        private val POLL: Duration = Duration.ofMillis(50)
        private val json: JsonMapper = JsonMapper.builder().build()
    }
}
