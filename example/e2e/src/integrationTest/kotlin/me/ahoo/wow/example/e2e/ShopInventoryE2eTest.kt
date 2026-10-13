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

package me.ahoo.wow.example.e2e

import me.ahoo.test.asserts.assert
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration

/**
 * The example's two services end to end (decision D144): the shop and the inventory, each in a JVM of its own on
 * PostgreSQL (a database each), exchanging commands, receipts and the order stream over Kafka.
 *
 * - The shop's `OrderSaga` sends `inventory.stock.ReserveStock` for each product of a new order to
 *   `wow.inventory.commands`; the inventory executes it from its command intake (decisions D33, D135, D137).
 * - A reservation the inventory rejects comes back on `wow.example.receipts`, and the saga's `@OnRejected` handler
 *   cancels the order (decision D136).
 * - The inventory's `OrderStockSaga` reads `wow.example.order.state` (decisions D134, D156) and commits the
 *   reservations of a shipped order, or releases those of a cancelled one.
 *
 * A wait follows the order only within the shop: `committed > handled(example/OrderSaga.onOrderCreated)` ends once
 * the reservations are on the inventory's topic. What happens in the inventory is read from its state, polled until a
 * deadline; waits across contexts are work package 6.6.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopInventoryE2eTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val postgres = PostgreSQLContainer("postgres:17-alpine").withDatabaseName(SHOP_DATABASE).also { it.start() }

    init {
        postgres.execInContainer("psql", "-U", postgres.username, "-d", SHOP_DATABASE, "-c", "CREATE DATABASE $INVENTORY_DATABASE")
            .exitCode.assert().isEqualTo(0)
        admin().use { admin ->
            admin.createTopics(
                listOf(
                    // As many partitions as the inventory's log (decision D135).
                    NewTopic(COMMANDS, PARTITIONS, 1),
                    NewTopic(RECEIPTS, 1, 1),
                    NewTopic(ORDERS, 2, 1),
                    NewTopic("wow.example.cart.state", 1, 1),
                    NewTopic("wow.inventory.stock.state", 1, 1),
                ),
            ).all().get()
        }
    }

    private val inventory = ExampleService.start("inventory", INVENTORY_MAIN, arguments(INVENTORY_DATABASE, "inventory-1"))
    private val shop = ExampleService.start("shop", SHOP_MAIN, arguments(SHOP_DATABASE, "shop-1"))

    @AfterAll
    fun close() {
        listOf(shop, inventory).forEach { it.close() }
        postgres.stop()
        kafka.stop()
    }

    private fun arguments(database: String, node: String): List<String> = listOf(
        "--spring.profiles.active=postgres",
        "--spring.datasource.url=jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
        "--wow.kafka.bootstrap-servers=${kafka.bootstrapServers}",
        "--wow.node.id=$node",
        "--wow.log.partitions=$PARTITIONS",
        "--wow.cluster.lease-ttl=5s",
        "--wow.cluster.renew-interval=1s",
        "--wow.cluster.member-ttl=5s",
        "--wow.cluster.heartbeat-interval=1s",
        "--wow.cluster.reconcile-interval=200ms",
        "--wow.cluster.route-refresh-interval=200ms",
        "--wow.subscription.checkpoint-interval=100ms",
    )

    @Test
    fun `an order in stock should be reserved, and shipping it should commit the reservation`() {
        receive("apple", 10)
        val before = stock("apple")
        val id = placeOrder(line("apple", APPLE, 2))
        eventually("apple reserved for $id") { stock("apple").reserved(id) == 2 }
        stock("apple").available.assert().isEqualTo(before.available - 2)

        pay(id, APPLE.multiply(BigDecimal(2)))
        shop.post("/example/order/$id/ship-order").status.assert().isEqualTo(OK)
        eventually("the reservation of $id committed") { stock("apple").reserved(id) == null }
        val after = stock("apple")
        after.onHand.assert().isEqualTo(before.onHand - 2)
        after.available.assert().isEqualTo(before.available - 2)
    }

    @Test
    fun `an order out of stock should be cancelled through the receipt, and its other reservations released`() {
        receive("pear", 5)
        receive("durian", 1)
        val pear = stockVersion("pear")
        val durian = stock("durian")
        val id = placeOrder(line("pear", PEAR, 1), line("durian", DURIAN, durian.available + 1))

        eventually("$id cancelled") { order(id)?.get("status")?.asString() == "CANCELLED" }
        order(id)!!["cancelReason"].asString().assert().contains("durian").contains("are available")
        // The pear was reserved, then released when the inventory read the cancellation: two commits.
        eventually("the pear of $id released") { stockVersion("pear") == pear + 2 }
        stock("pear").reserved(id).assert().isNull()
        // The refused reservation wrote nothing.
        stockVersion("durian").assert().isEqualTo(durian.version)
    }

    @Test
    fun `commands and receipts delivered again should not reserve or cancel twice`() {
        receive("apple", 10)
        receive("durian", 1)
        val reserved = placeOrder(line("apple", APPLE, 1))
        eventually("apple reserved for $reserved") { stock("apple").reserved(reserved) == 1 }
        val cancelled = placeOrder(line("durian", DURIAN, stock("durian").available + 1))
        eventually("$cancelled cancelled") { order(cancelled)?.get("status")?.asString() == "CANCELLED" }
        val apple = stockVersion("apple")

        // Every command and receipt so far, delivered again: each message copied to its partition.
        redeliver(COMMANDS)
        redeliver(RECEIPTS)

        // The copies of the apple's commands are ahead of this one in its partition, and one aggregate's commands
        // run in order: once it is reserved, they were all decided.
        val next = placeOrder(line("apple", APPLE, 1))
        eventually("apple reserved for $next") { stock("apple").reserved(next) == 1 }
        stockVersion("apple").assert().isEqualTo(apple + 1)
        stock("apple").reserved(reserved).assert().isEqualTo(1)
        // Likewise for the receipts: a later refusal handled means the copies ahead of it were handled.
        val later = placeOrder(line("durian", DURIAN, stock("durian").available + 1))
        eventually("$later cancelled") { order(later)?.get("status")?.asString() == "CANCELLED" }
        orderVersion(cancelled).assert().isEqualTo(2)
    }

    @Test
    fun `a crash of the inventory should neither lose a shipped order nor commit it twice`() {
        receive("apple", 10)
        val id = placeOrder(line("apple", APPLE, 1))
        eventually("apple reserved for $id") { stock("apple").reserved(id) == 1 }
        val version = stockVersion("apple")
        pay(id, APPLE)
        shop.post("/example/order/$id/ship-order").status.assert().isEqualTo(OK)

        // Killed at once, before or while it reads the shipped order, and started again on the same database: it
        // reads the order stream and its command topic from its last checkpoints.
        inventory.restart()
        eventually("the reservation of $id committed after the restart") { stock("apple").reserved(id) == null }
        stockVersion("apple").assert().isEqualTo(version + 1)
        // A fresh order goes through the restarted inventory as before.
        val next = placeOrder(line("apple", APPLE, 1))
        eventually("apple reserved for $next") { stock("apple").reserved(next) == 1 }
        stockVersion("apple").assert().isEqualTo(version + 2)
    }

    private fun receive(product: String, quantity: Int) {
        val received = inventory.post("/inventory/stock/$product/receive-stock", """{"quantity":$quantity}""")
        received.status.assert().describedAs(received.json.toString()).isEqualTo(OK)
    }

    /** Places an order of [lines], waiting until the saga's reservations are on the inventory's topic. */
    private fun placeOrder(vararg lines: String): String {
        val placed = shop.post(
            "/example/order/create-order",
            """{"items":[${lines.joinToString(",")}],"address":$ADDRESS}""",
            mapOf("Wow-Wait" to "committed > handled(example/OrderSaga.onOrderCreated)"),
        )
        placed.status.assert().describedAs(placed.json.toString()).isEqualTo(OK)
        placed.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        placed.json["hop"]["subscription"].asString().assert().isEqualTo("OrderSaga.onOrderCreated")
        return placed.json["ref"]["id"].asString()
    }

    private fun pay(id: String, amount: BigDecimal) {
        shop.post("/example/order/$id/pay-order", """{"paymentId":"payment-$id","amount":$amount}""")
            .status.assert().isEqualTo(OK)
    }

    private fun line(product: String, price: BigDecimal, quantity: Int) =
        """{"productId":"$product","price":$price,"quantity":$quantity}"""

    /** A product's stock as the inventory reads it, at its [version]. */
    private data class Stock(val version: Int, val onHand: Int, val available: Int, val reservations: Map<String, Int>) {
        fun reserved(order: String): Int? = reservations[order]
    }

    private fun stock(product: String): Stock {
        val answer = checkNotNull(inventory.state("inventory", "stock", product)) { "No stock of $product." }.json
        val state = answer["state"]
        return Stock(
            version = answer["version"].asInt(),
            onHand = state["onHand"].asInt(),
            available = state["available"].asInt(),
            reservations = state["reservations"].properties().associate { (order, quantity) -> order to quantity.asInt() },
        )
    }

    private fun stockVersion(product: String): Int = stock(product).version

    private fun order(id: String): JsonNode? = shop.state("example", "order", id)?.json?.get("state")

    private fun orderVersion(id: String): Int = checkNotNull(shop.state("example", "order", id)).json["version"].asInt()

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Not reached in $DEADLINE: $what.\n--- shop ---\n${shop.tail()}\n--- inventory ---\n${inventory.tail()}",
                )
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    private fun admin(): Admin = Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))

    /** Copies every message of [topic] so far to the end of its partition, with its key and headers: a redelivery. */
    private fun redeliver(topic: String) {
        val consumer = KafkaConsumer<ByteArray, ByteArray>(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ),
        )
        val producer = KafkaProducer<ByteArray, ByteArray>(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            ),
        )
        consumer.use {
            producer.use {
                val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
                val ends = consumer.endOffsets(partitions)
                consumer.assign(partitions)
                consumer.seekToBeginning(partitions)
                var copied = 0
                while (partitions.any { consumer.position(it) < ends.getValue(it) }) {
                    consumer.poll(Duration.ofMillis(500)).filter { it.offset() < ends.getValue(TopicPartition(topic, it.partition())) }
                        .forEach { record ->
                            producer.send(ProducerRecord(topic, record.partition(), record.key(), record.value(), record.headers()))
                            copied++
                        }
                }
                producer.flush()
                copied.assert().describedAs("messages copied on $topic").isGreaterThan(0)
            }
        }
    }

    private companion object {
        const val OK = 200
        const val PARTITIONS = 4
        const val SHOP_DATABASE = "shop"
        const val INVENTORY_DATABASE = "inventory"
        const val COMMANDS = "wow.inventory.commands"
        const val RECEIPTS = "wow.example.receipts"
        const val ORDERS = "wow.example.order.state"
        const val SHOP_MAIN = "me.ahoo.wow.example.shop.server.ShopServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.inventory.server.InventoryServerKt"
        const val ADDRESS = """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1"}"""
        val APPLE = BigDecimal("2.50")
        val PEAR = BigDecimal("4.00")
        val DURIAN = BigDecimal("30.00")
        val DEADLINE: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
