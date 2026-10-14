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

import me.ahoo.test.asserts.assert
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The basket, ordering and inventory services end to end (work packages E.2 to E.4), each in a JVM of its own as its
 * `application.yaml` configures it with the `postgres` and `kafka` profiles, each on a PostgreSQL database of its own,
 * talking over Kafka:
 *
 * - **Checkout.** The basket's checkout saga calls the ordering's command gateway with `eshop-ordering.order.SubmitOrder`
 *   synchronously (decision D193), with the basket service's credential, for the buyer.
 * - **Stock.** The ordering's order-process saga sends `eshop-inventory.stock.ReserveStock` to
 *   `wow.eshop-inventory.commands`; the inventory sends the receipt of each reservation it commits to
 *   `wow.eshop-ordering.receipts`, and the saga's `@OnCommitted` confirms the order's product (decision D192).
 * - **Rejection.** A reservation the inventory rejects comes back as a receipt on `wow.eshop-ordering.receipts`, and the
 *   saga's `@OnRejected` cancels the order; the cancelled order releases the other reservations.
 * - **Grace period.** An order whose stock is confirmed cancels itself, by a scheduled command, when it is not paid in
 *   time.
 *
 * The payment service is not here, so the test pays the order itself, and an order it does not pay waits out its
 * grace period; `EshopPaymentFlowTest` runs the payment service.
 *
 * The services do not share the test's class path, as `EshopSharedJvmTest`'s do: each JVM reads its own server's
 * `application.yaml`, as a deployment does, where one class path would give them one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EshopOrderFlowTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val postgres = PostgreSQLContainer("postgres:17-alpine").withDatabaseName(BASKET_DATABASE).also { it.start() }

    init {
        listOf(ORDERING_DATABASE, INVENTORY_DATABASE).forEach { database ->
            postgres.execInContainer("psql", "-U", postgres.username, "-d", BASKET_DATABASE, "-c", "CREATE DATABASE $database")
                .exitCode.assert().isEqualTo(0)
        }
        // The node does not create topics; each command topic has as many partitions as its context's log.
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("wow.eshop-ordering.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-inventory.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-basket.receipts", 1, 1),
                    NewTopic("wow.eshop-ordering.receipts", 1, 1),
                    NewTopic("wow.eshop-basket.basket.state", 2, 1),
                    NewTopic("wow.eshop-ordering.order.state", 2, 1),
                    NewTopic("wow.eshop-inventory.stock.state", 2, 1),
                ),
            ).all().get()
        }
    }

    private val inventory = EshopService.start("inventory", INVENTORY_MAIN, arguments(INVENTORY_DATABASE))
    private val ordering = EshopService.start(
        "ordering",
        ORDERING_MAIN,
        arguments(ORDERING_DATABASE) + listOf(
            "--eshop.ordering.payment-grace-period=${GRACE.toMillis()}ms",
            "--wow.timers.poll-interval=200ms",
        ),
        internalPort = EshopService.freePort(),
    )
    private val basket = EshopService.start(
        "basket",
        BASKET_MAIN,
        arguments(BASKET_DATABASE) + EshopStack.callOrdering(ordering.internalPort),
    )

    @AfterAll
    fun close() {
        listOf(basket, ordering, inventory).forEach { it.close() }
        postgres.stop()
        kafka.stop()
    }

    private fun arguments(database: String): List<String> = backendArguments(postgres, kafka, database, PARTITIONS)

    @Test
    fun `a checkout should become an order, reserved, paid, shipped and taken out of stock`() {
        receive("mug", 10)
        receive("cap", 5)
        addItem("alice", "mug", MUG_PRICE, 2)
        addItem("alice", "cap", CAP_PRICE, 1)

        // The wait follows the checkout into the ordering context: it answers once the order is committed there.
        val checkedOut = checkout("alice", mapOf(WAIT to CHECKOUT_CHAIN, WAIT_TIMEOUT to "30s"))
        checkedOut["status"].asString().assert().describedAs(checkedOut.toString()).isEqualTo("SUCCEEDED")
        checkedOut["hop"]["context"].asString().assert().isEqualTo("eshop-ordering")
        checkedOut["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        checkedOut["ref"]["id"].asString().assert().isEqualTo("alice-1")
        basketItems("alice").assert().isEmpty()

        eventually("alice-1 confirmed") { orderStatus("alice-1") == "STOCK_CONFIRMED" }
        // The order belongs to its buyer (decision D189): the checkout saga's call carried the basket's scope.
        val view = json.readTree(ordering.get("/eshop-ordering/order/alice-1/state", buyer("alice")).body)
        view["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        ordering.get("/eshop-ordering/order/alice-1/state", buyer("bob")).status.assert().isEqualTo(FORBIDDEN)
        stock("mug")["reservations"]["alice-1"].asInt().assert().isEqualTo(2)
        stock("cap")["reservations"]["alice-1"].asInt().assert().isEqualTo(1)

        val paid = pay("alice-1", "29.00")
        paid.status.assert().describedAs(paid.body).isEqualTo(OK)
        ordering.post("/eshop-ordering/order/alice-1/ship-order", "{}").status.assert().isEqualTo(OK)
        orderStatus("alice-1").assert().isEqualTo("SHIPPED")

        eventually("the reservations of alice-1 committed") {
            stock("mug")["reservations"]["alice-1"] == null && stock("cap")["reservations"]["alice-1"] == null
        }
        stock("mug")["onHand"].asInt().assert().isEqualTo(8)
        stock("cap")["onHand"].asInt().assert().isEqualTo(4)
        // Long past its grace period, the paid order is still shipped: paying it cancelled the scheduled cancellation.
        Thread.sleep(GRACE.plusSeconds(2).toMillis())
        orderStatus("alice-1").assert().isEqualTo("SHIPPED")
    }

    @Test
    fun `a stock too short should cancel the order through the receipt and release its other reservations`() {
        receive("pen", 1)
        receive("pad", 10)
        addItem("bob", "pad", PAD_PRICE, 1)
        addItem("bob", "pen", PEN_PRICE, 3)
        checkout("bob")

        eventually("bob-1 cancelled") { orderStatus("bob-1") == "CANCELLED" }
        order("bob-1")["cancelReason"].asString().assert().contains("pen").contains("are available")
        // Whether the pad was reserved before the cancellation or not, the order holds none of it in the end.
        eventually("the pad of bob-1 released") { stock("pad")["reservations"]["bob-1"] == null }
        stock("pad")["available"].asInt().assert().isEqualTo(10)
        stock("pen")["available"].asInt().assert().isEqualTo(1)
    }

    @Test
    fun `an order not paid within its grace period should cancel itself and release its stock`() {
        receive("hat", 4)
        addItem("carol", "hat", HAT_PRICE, 2)
        checkout("carol")
        eventually("carol-1 confirmed") { orderStatus("carol-1") == "STOCK_CONFIRMED" }
        stock("hat")["available"].asInt().assert().isEqualTo(2)

        eventually("carol-1 cancelled for want of payment") { orderStatus("carol-1") == "CANCELLED" }
        order("carol-1")["cancelReason"].asString().assert().contains("payment grace period")
        eventually("the hats of carol-1 released") { stock("hat")["available"].asInt() == 4 }
        val late = pay("carol-1", "30.00")
        late.status.assert().isEqualTo(CONFLICT)
        json.readTree(late.body)["code"].asString().assert().isEqualTo("eshop-ordering.order.wrong-status")
    }

    private fun receive(product: String, quantity: Int) {
        val received = inventory.post("/eshop-inventory/stock/$product/receive-stock", """{"quantity":$quantity}""")
        received.status.assert().describedAs(received.body).isEqualTo(OK)
    }

    private fun addItem(buyer: String, product: String, price: String, quantity: Int) {
        val added = basket.post(
            "/eshop-basket/basket/$buyer/add-basket-item",
            """{"productId":"$product","unitPrice":$price,"quantity":$quantity}""",
            buyer(buyer),
        )
        added.status.assert().describedAs(added.body).isEqualTo(OK)
    }

    private fun checkout(buyer: String, headers: Map<String, String> = emptyMap()): JsonNode {
        val checkedOut = basket.post("/eshop-basket/basket/$buyer/checkout-basket", "{}", headers + buyer(buyer))
        checkedOut.status.assert().describedAs(checkedOut.body).isEqualTo(OK)
        return json.readTree(checkedOut.body)
    }

    /** The headers of the buyer [id] ([me.ahoo.wow.example.eshop.basket.server.BasketCallers]). */
    private fun buyer(id: String): Map<String, String> = mapOf(USER to id)

    private fun pay(order: String, amount: String): Answer =
        ordering.post("/eshop-ordering/order/$order/pay-order", """{"paymentId":"payment-$order","amount":$amount}""")

    /** The state of the aggregate [id], or null while it has none. */
    private fun EshopService.state(path: String): JsonNode? {
        val answer = get(path)
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET $path answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)["state"]
    }

    private fun basketItems(buyer: String): List<JsonNode> =
        checkNotNull(basket.state("/eshop-basket/basket/$buyer/state"))["items"].values().toList()

    private fun order(id: String): JsonNode = checkNotNull(ordering.state("/eshop-ordering/order/$id/state")) { "No order $id." }

    private fun orderStatus(id: String): String? = ordering.state("/eshop-ordering/order/$id/state")?.get("status")?.asString()

    private fun stock(product: String): JsonNode =
        checkNotNull(inventory.state("/eshop-inventory/stock/$product/state")) { "No stock of $product." }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Not reached in $DEADLINE: $what.\n--- basket ---\n${basket.output().takeLast(TAIL)}" +
                        "\n--- ordering ---\n${ordering.output().takeLast(TAIL)}" +
                        "\n--- inventory ---\n${inventory.output().takeLast(TAIL)}",
                )
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val FORBIDDEN = 403
        const val USER = "X-User"
        const val TAIL = 4_000
        const val WAIT = "Wow-Wait"
        const val WAIT_TIMEOUT = "Wow-Wait-Timeout"
        const val BASKET_MAIN = "me.ahoo.wow.example.eshop.basket.server.BasketServerKt"
        const val ORDERING_MAIN = "me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
        const val CHECKOUT_CHAIN = "committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)"
        const val PARTITIONS = 4
        const val BASKET_DATABASE = "eshop_basket"
        const val ORDERING_DATABASE = "eshop_ordering"
        const val INVENTORY_DATABASE = "eshop_inventory"
        const val MUG_PRICE = "8.50"
        const val CAP_PRICE = "12.00"
        const val PEN_PRICE = "2.00"
        const val PAD_PRICE = "4.00"
        const val HAT_PRICE = "15.00"
        val GRACE: Duration = Duration.ofSeconds(8)
        val DEADLINE: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
