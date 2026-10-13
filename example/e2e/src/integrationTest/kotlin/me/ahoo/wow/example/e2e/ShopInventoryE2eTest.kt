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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * Waits follow the order across the two services (design 04 §6.3, work package 6.6): `create-order` waits for
 * `committed > handled(example/OrderSaga.onOrderCreated) > committed(inventory)`, so it answers once the inventory has
 * committed every reservation, or at once with the inventory's refusal; `ship-order` waits for the inventory's saga
 * on the order stream and the commit it derives. The inventory signals the shop node the wait started on through its
 * internal port (`/wow/internal/signals`). Effects the waits do not cover, such as a cancellation by receipt, are read
 * from the state, polled until a deadline.
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
        // The signals a sender drops are logged at DEBUG.
        "--logging.level.me.ahoo.wow.http.internal.HttpSignalSender=DEBUG",
    )

    @Test
    fun `an order in stock should be reserved, and shipping it should commit the reservation`() {
        receive("apple", 10)
        val before = stock("apple")
        // The wait ends once the inventory committed the reservation; its state is read from the snapshot, written next.
        val id = placeOrder(line("apple", APPLE, 2))
        eventually("apple reserved for $id") { stock("apple").reserved(id) == 2 }
        stock("apple").available.assert().isEqualTo(before.available - 2)

        pay(id, APPLE.multiply(BigDecimal(2)))
        // The inventory's saga reads the shipped order from the order stream and commits the reservation.
        val shipped = shop.post("/example/order/$id/ship-order", headers = mapOf(WAIT to SHIP_CHAIN))
        shipped.status.assert().describedAs(shipped.json.toString()).isEqualTo(OK)
        shipped.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        shipped.json["hop"]["context"].asString().assert().isEqualTo("inventory")
        shipped.json["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        shipped.json["ref"]["id"].asString().assert().isEqualTo("apple")
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
        val began = System.nanoTime()
        val events = placeStreaming(line("pear", PEAR, 1), line("durian", DURIAN, durian.available + 1))
        // The inventory's refusal ends the wait at once, well before its deadline.
        Duration.ofNanos(System.nanoTime() - began).assert().isLessThan(Duration.ofSeconds(15))
        val id = events.first()["ref"]["id"].asString()
        val refused = events.last()
        refused["status"].asString().assert().isEqualTo("REJECTED")
        refused["error"]["code"].asString().assert().isEqualTo("inventory.stock.insufficient")
        refused["hop"]["context"].asString().assert().isEqualTo("inventory")
        refused["ref"]["id"].asString().assert().isEqualTo("durian")

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
        val cancelled = refuseOrder(line("durian", DURIAN, stock("durian").available + 1))
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
        eventually("apple reserved for $reserved") { stock("apple").reserved(reserved) == 1 }
        // Likewise for the receipts: a later refusal handled means the copies ahead of it were handled.
        val later = refuseOrder(line("durian", DURIAN, stock("durian").available + 1))
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

    @Test
    fun `a wait should end by its deadline, and a signal to a node killed mid-wait should be dropped`() {
        receive("apple", 10)
        val body = orderBody(line("apple", APPLE, 1))
        // The inventory hangs: the reservations wait on its topic.
        inventory.pause()
        val id = try {
            // A wait that cannot complete answers at its deadline, at the last hop it reached.
            val began = System.nanoTime()
            val timedOut = shop.post(CREATE_ORDER, body, mapOf(WAIT to CREATE_CHAIN, WAIT_TIMEOUT to "2s"))
            val waited = Duration.ofNanos(System.nanoTime() - began)
            waited.assert().isGreaterThanOrEqualTo(Duration.ofMillis(1_900)).isLessThan(Duration.ofSeconds(10))
            timedOut.json["code"].asString().assert().describedAs(timedOut.json.toString()).isEqualTo("wow.wait.timeout")
            timedOut.json["result"]["hop"]["subscription"].asString().assert().isEqualTo("OrderSaga.onOrderCreated")

            // A wait whose node is killed once the saga's hop is reached: its caller learns at once, not at the
            // deadline, and the inventory's signal finds no one.
            val events = CopyOnWriteArrayList<JsonNode>()
            val sagaReached = CountDownLatch(1)
            val caller = thread {
                runCatching {
                    shop.stream(CREATE_ORDER, body, mapOf(WAIT to CREATE_CHAIN, WAIT_TIMEOUT to "60s")) { event ->
                        events.add(event)
                        if (event["hop"]["subscription"]?.asString() == "OrderSaga.onOrderCreated") sagaReached.countDown()
                    }
                }
            }
            sagaReached.await(30, TimeUnit.SECONDS).assert().describedAs("the saga's hop reached").isTrue()
            shop.kill()
            caller.join(Duration.ofSeconds(10).toMillis())
            caller.isAlive.assert().describedAs("the caller still waits for its killed node").isFalse()
            events.map { it["hop"]["stage"].asString() }.assert().containsExactly("COMMITTED", "HANDLED")
            events.first()["ref"]["id"].asString()
        } finally {
            inventory.resume()
        }
        // The inventory goes on: it reserves the order and drops its signal to the killed node, without retrying.
        eventually("apple reserved for $id") { stock("apple").reserved(id) == 1 }
        eventually("the signal to the killed shop dropped") { inventory.log.readText().contains("Dropped the signal of wait") }

        // The shop again: a fresh order's wait goes through as before.
        shop.relaunch()
        val next = placeOrder(line("apple", APPLE, 1))
        eventually("apple reserved for $next") { stock("apple").reserved(next) == 1 }
    }

    private fun receive(product: String, quantity: Int) {
        val received = inventory.post("/inventory/stock/$product/receive-stock", """{"quantity":$quantity}""")
        received.status.assert().describedAs(received.json.toString()).isEqualTo(OK)
    }

    private fun orderBody(vararg lines: String) = """{"items":[${lines.joinToString(",")}],"address":$ADDRESS}"""

    /** Places an order of [lines], waiting until the inventory committed its reservations; returns the order's id. */
    private fun placeOrder(vararg lines: String): String {
        val events = placeStreaming(*lines)
        val reserved = events.last()
        reserved["status"].asString().assert().describedAs(reserved.toString()).isEqualTo("SUCCEEDED")
        reserved["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        reserved["hop"]["context"].asString().assert().isEqualTo("inventory")
        reserved["function"]["context"].asString().assert().isEqualTo("inventory")
        return events.first()["ref"]["id"].asString()
    }

    /** Places an order of [lines] that the inventory refuses; returns the order's id. */
    private fun refuseOrder(vararg lines: String): String {
        val events = placeStreaming(*lines)
        events.last()["status"].asString().assert().describedAs(events.last().toString()).isEqualTo("REJECTED")
        return events.first()["ref"]["id"].asString()
    }

    /**
     * Places an order of [lines] with the cross-context wait, as an event stream: each hop's result in order, the
     * order's commit first (its ref is the order's), the reservations' commits or the refusal last.
     */
    private fun placeStreaming(vararg lines: String): List<JsonNode> {
        val events = ArrayList<JsonNode>()
        shop.stream(CREATE_ORDER, orderBody(*lines), mapOf(WAIT to CREATE_CHAIN, WAIT_TIMEOUT to "30s")) { events.add(it) }
        events.first()["hop"]["context"].asString().assert().describedAs(events.toString()).isEqualTo("example")
        return events
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
        const val CREATE_ORDER = "/example/order/create-order"
        const val WAIT = "Wow-Wait"
        const val WAIT_TIMEOUT = "Wow-Wait-Timeout"
        const val CREATE_CHAIN = "committed > handled(example/OrderSaga.onOrderCreated) > committed(inventory)"
        const val SHIP_CHAIN = "handled(inventory/OrderStockSaga.onOrderShipped) > committed(inventory)"
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
