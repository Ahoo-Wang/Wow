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

package me.ahoo.wow.example.eshop.e2e

import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.cocache.StateViewCaches
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The payment of an order end to end (work package E.5): the ordering, inventory and payment services, each in a JVM
 * of its own as its `application.yaml` configures it with the `postgres` and `kafka` profiles, each on a PostgreSQL
 * database of its own, talking over Kafka. The simulated gateway approves up to 100.00 and declines above it.
 *
 * - **Request.** The payment's order-payment saga reads `OrderStockConfirmed` from `wow.eshop-ordering.order.state`
 *   and requests the order's payment; the gateway saga charges the gateway and records its answer.
 * - **Success.** An approved payment sends `eshop-ordering.order.PayOrder` to `wow.eshop-ordering.commands`: the order
 *   is paid, and then shipped, which commits its reservations.
 * - **Failure.** A declined payment sends `eshop-ordering.order.CancelOrder`: the order is cancelled, and the ordering's
 *   order-process saga releases its reservations.
 *
 * - **The orders' cache.** The ordering and the payment services run their `redis` profile, on Redis in a container:
 *   the ordering service writes each order into CoCache after its commits (`wow.cocache.publish`, its sink
 *   subscription), and the payment's gateway saga reads it there, `StateViewCache<OrderStateView>`, to say what a
 *   charge is for. The test reads the cache as a third service does, with no read of the ordering service.
 *
 * The orders are submitted to the ordering service directly; the basket's checkout is `EshopOrderFlowTest`'s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EshopPaymentFlowTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine").withDatabaseName(ORDERING_DATABASE).also { it.start() }
    private val redis = GenericContainer(REDIS_IMAGE).withExposedPorts(REDIS_PORT).also { it.start() }

    init {
        listOf(INVENTORY_DATABASE, PAYMENT_DATABASE).forEach { database ->
            postgres.execInContainer(
                "psql",
                "-U",
                postgres.username,
                "-d",
                ORDERING_DATABASE,
                "-c",
                "CREATE DATABASE $database"
            )
                .exitCode.assert().isEqualTo(0)
        }
        // The node does not create topics; each command topic has as many partitions as its context's log.
        Admin.create(
            mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)
        ).use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("wow.eshop-ordering.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-inventory.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-ordering.receipts", 1, 1),
                    NewTopic("wow.eshop-payment.receipts", 1, 1),
                    NewTopic("wow.eshop-ordering.order.state", 2, 1),
                    NewTopic("wow.eshop-inventory.stock.state", 2, 1),
                    NewTopic("wow.eshop-payment.payment.state", 2, 1),
                ),
            ).all().get()
        }
    }

    private val inventory = EshopService.start("inventory", INVENTORY_MAIN, arguments(INVENTORY_DATABASE))
    private val ordering = EshopService.start("ordering", ORDERING_MAIN, cachedArguments(ORDERING_DATABASE))
    private val payment = EshopService.start(
        "payment",
        PAYMENT_MAIN,
        cachedArguments(PAYMENT_DATABASE) + "--eshop.payment.gateway.limit=$LIMIT",
    )

    /** The orders' cache, as a third service reads it: on the same Redis, reading nothing of the ordering service. */
    private val reader = CacheRuntime.builder()
        .backend(RedisBackend.standalone(RedisURI.create(redis.host, redis.getMappedPort(REDIS_PORT))))
        .build()
    private val orders = StateViewCaches(StateViewCaches.CacheFactory.of(reader)) {
        error("The ordering service writes the orders: the test reads none from it.")
    }.of<OrderStateView>()

    @AfterAll
    fun close() {
        reader.close()
        listOf(payment, ordering, inventory).forEach { it.close() }
        postgres.stop()
        kafka.stop()
        redis.stop()
    }

    private fun arguments(database: String): List<String> = backendArguments(postgres, kafka, database, PARTITIONS)

    /** A service's arguments in its `redis` profile too, on the test's Redis. */
    private fun cachedArguments(database: String): List<String> =
        backendArguments(postgres, kafka, database, PARTITIONS, profiles = listOf("dev", "redis")) + listOf(
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${redis.getMappedPort(REDIS_PORT)}",
        )

    @Test
    fun `a payment the gateway approves should pay the order`() {
        receive("mug", 10)
        receive("cap", 5)
        submit(
            "alice-1",
            """{"productId":"mug","unitPrice":8.50,"quantity":2},{"productId":"cap","unitPrice":12.00,"quantity":1}"""
        )

        eventually("alice-1 paid") { orderStatus("alice-1") == "PAID" }
        order("alice-1")["paymentId"].asString().assert().isEqualTo("alice-1")
        val paid = payment("alice-1")
        paid["status"].asString().assert().isEqualTo("SUCCEEDED")
        paid["amount"].decimalValue().assert().isEqualByComparingTo("29.00")
        paid["transactionId"].asString().assert().startsWith("simulated-")

        // The ordering service wrote the paid order into the cache, which another service reads without asking it.
        eventually("the cache holds alice-1 paid") { cachedStatus("alice-1") == OrderStatus.PAID }
        runBlocking { orders.get(DEFAULT_TENANT, "alice-1") }!!.totalAmount.assert().isEqualByComparingTo("29.00")

        ordering.post("/eshop-ordering/order/alice-1/ship-order", "{}").status.assert().isEqualTo(OK)
        eventually("the cache holds alice-1 shipped") { cachedStatus("alice-1") == OrderStatus.SHIPPED }
        eventually("the reservations of alice-1 committed") {
            stock("mug")["reservations"]["alice-1"] == null && stock("cap")["reservations"]["alice-1"] == null
        }
        stock("mug")["onHand"].asInt().assert().isEqualTo(8)
        stock("cap")["onHand"].asInt().assert().isEqualTo(4)
    }

    @Test
    fun `a payment the gateway declines should cancel the order and release its stock`() {
        receive("hat", 5)
        submit("bob-1", """{"productId":"hat","unitPrice":50.00,"quantity":3}""")

        eventually("bob-1 cancelled") { orderStatus("bob-1") == "CANCELLED" }
        // The gateway saga said what the charge was for: the order's items, read from the ordering's cache.
        order("bob-1")["cancelReason"].asString().assert().contains("The payment failed").contains("(3 × hat)")
            .contains("over the limit")
        eventually("the cache holds bob-1 cancelled") { cachedStatus("bob-1") == OrderStatus.CANCELLED }
        val failed = payment("bob-1")
        failed["status"].asString().assert().isEqualTo("FAILED")
        failed["amount"].decimalValue().assert().isEqualByComparingTo("150.00")
        eventually("the hats of bob-1 released") { stock("hat")["available"].asInt() == 5 }
        stock("hat")["reservations"]["bob-1"].assert().isNull()
    }

    private fun receive(product: String, quantity: Int) {
        val received = inventory.post("/eshop-inventory/stock/$product/receive-stock", """{"quantity":$quantity}""")
        received.status.assert().describedAs(received.body).isEqualTo(OK)
    }

    private fun submit(order: String, items: String) {
        val submitted = ordering.post("/eshop-ordering/order/$order/submit-order", """{"items":[$items]}""")
        submitted.status.assert().describedAs(submitted.body).isEqualTo(OK)
    }

    /** The state of the aggregate at [path], or null while it has none. */
    private fun EshopService.state(path: String): JsonNode? {
        val answer = get(path)
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET $path answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)["state"]
    }

    private fun order(id: String): JsonNode = checkNotNull(ordering.state("/eshop-ordering/order/$id/state")) {
        "No order $id."
    }

    private fun orderStatus(id: String): String? = ordering.state(
        "/eshop-ordering/order/$id/state"
    )?.get("status")?.asString()

    private fun payment(id: String): JsonNode =
        checkNotNull(payment.state("/eshop-payment/payment/$id/state")) { "No payment $id." }

    private fun stock(product: String): JsonNode =
        checkNotNull(inventory.state("/eshop-inventory/stock/$product/state")) { "No stock of $product." }

    /** The order's status as the cache holds it, null while it holds none. */
    private fun cachedStatus(id: String): OrderStatus? =
        runCatching { runBlocking { orders.get(DEFAULT_TENANT, id) } }.getOrNull()?.status

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Not reached in $DEADLINE: $what.\n--- ordering ---\n${ordering.output().takeLast(TAIL)}" +
                        "\n--- inventory ---\n${inventory.output().takeLast(TAIL)}" +
                        "\n--- payment ---\n${payment.output().takeLast(TAIL)}",
                )
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val TAIL = 4_000
        const val ORDERING_MAIN = "me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
        const val PAYMENT_MAIN = "me.ahoo.wow.example.eshop.payment.server.PaymentServerKt"
        const val PARTITIONS = 4
        const val LIMIT = "100.00"
        const val ORDERING_DATABASE = "eshop_ordering"
        const val INVENTORY_DATABASE = "eshop_inventory"
        const val PAYMENT_DATABASE = "eshop_payment"
        const val REDIS_IMAGE = "redis:7.4-alpine"
        const val REDIS_PORT = 6379

        /** The tenant of a development caller that names none. */
        const val DEFAULT_TENANT = Caller.DEFAULT_TENANT
        val DEADLINE: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
