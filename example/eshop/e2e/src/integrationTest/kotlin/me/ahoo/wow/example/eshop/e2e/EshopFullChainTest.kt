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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The whole eShop end to end (work package E.6): [EshopStack]'s five services on PostgreSQL, Kafka and ClickHouse,
 * the ordering on two nodes sharing its database, so a saga's commands are routed to their partitions' owners (G32).
 *
 * - **Full chain.** Products created and published in the catalog, stock received, a basket checked out with one
 *   `Wow-Wait` chain that follows the order through the ordering, the inventory and the payment until the order's
 *   `PayOrder` is committed, then shipped, and its reservations committed.
 * - **Rejection.** A stock too short cancels the order (the receipt's `@OnRejected`), and no payment is requested.
 * - **Sales.** The sales dataset over the ClickHouse view of the order stream: the revenue by status and by day.
 * - **Node killed.** The ordering node owning the order's partition is killed (`SIGKILL`) right after a checkout,
 *   before the payment is requested, and a node of another id started in its place; the order completes with every
 *   effect once. The test logs how long the order's partition took no command, the lease's TTL included.
 *
 * The orders are read back from the ordering's state routes, which serve the snapshots: they trail the commits, so
 * every read of a state the flow is still changing is awaited.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EshopFullChainTest {
    private val stack = EshopStack(orderingNodes = 2)

    @AfterAll
    fun close() {
        stack.close()
    }

    @Test
    @Order(1)
    fun `a checkout should go from the catalog to a shipped order, its payment and its stock committed`() {
        product("mug", "Wow mug", "8.50")
        product("cap", "Wow cap", "12.00")
        receive("mug", 10)
        receive("cap", 5)
        // The storefront reads the prices from the catalog's published products.
        addItem("alice", "mug", price("mug"), 2)
        addItem("alice", "cap", price("cap"), 1)

        val checkedOut = checkout("alice", mapOf(WAIT to FULL_CHAIN, WAIT_TIMEOUT to "30s"))
        // The wait ends where the payment saga's PayOrder is committed in the ordering context.
        checkedOut["status"].asString().assert().describedAs(checkedOut.toString()).isEqualTo("SUCCEEDED")
        checkedOut["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        checkedOut["hop"]["context"].asString().assert().isEqualTo("eshop-ordering")
        checkedOut["function"]["name"].asString().assert().isEqualTo("PayOrder")
        checkedOut["ref"]["id"].asString().assert().isEqualTo("alice-1")
        basketItems("alice").assert().isEmpty()

        stack.eventually("alice-1 paid") { stack.orderStatus("alice-1") == "PAID" }
        // The order and its payment belong to the buyer (decision D189): each saga's command carried the scope on.
        stack.ownerOf(stack.anyOrdering, "/eshop-ordering/order/alice-1/state").assert().isEqualTo("alice")
        stack.ownerOf(stack.payment, "/eshop-payment/payment/alice-1/state").assert().isEqualTo("alice")
        // The stock belongs to the tenant: the reservations made for alice took no owner.
        stack.ownerOf(stack.inventory, "/eshop-inventory/stock/mug/state").assert().isNull()
        val paid = checkNotNull(stack.payment("alice-1"))
        paid["status"].asString().assert().isEqualTo("SUCCEEDED")
        paid["amount"].decimalValue().assert().isEqualByComparingTo("29.00")
        ship("alice-1")
        stack.eventually("the reservations of alice-1 committed") {
            stack.stock("mug")["reservations"]["alice-1"] == null && stack.stock("cap")["reservations"]["alice-1"] == null
        }
        stack.stock("mug")["onHand"].asInt().assert().isEqualTo(8)
        stack.stock("cap")["onHand"].asInt().assert().isEqualTo(4)
        orderEvents("alice-1").assert().containsExactly(
            "OrderSubmitted",
            "OrderAwaitingValidation",
            "OrderStockItemConfirmed",
            "OrderStockItemConfirmed",
            "OrderStockConfirmed",
            "ScheduleSet",
            "OrderPaid",
            "ScheduleCancelled",
            "OrderShipped",
        )
    }

    @Test
    @Order(2)
    fun `a stock too short should cancel the order and request no payment`() {
        product("pen", "Wow pen", "2.00")
        receive("pen", 1)
        addItem("bob", "pen", price("pen"), 3)
        checkout("bob")

        stack.eventually("bob-1 cancelled") { stack.orderStatus("bob-1") == "CANCELLED" }
        checkNotNull(stack.order("bob-1"))["cancelReason"].asString().assert().contains("pen")
        stack.stock("pen")["available"].asInt().assert().isEqualTo(1)
        stack.payment("bob-1").assert().isNull()
    }

    @Test
    @Order(3)
    fun `the sales dataset should sum the revenue of the orders by status and by day`() {
        val byStatus = """{"kind":"aggregate","filter":{"op":"IN","field":"aggregate_id","values":["alice-1","bob-1"]},""" +
            """"groupBy":[{"type":"TERMS","field":"status","alias":"status"}],""" +
            """"metrics":[{"type":"COUNT","alias":"orders"},$REVENUE],"sort":[{"field":"status","direction":"ASC"}]}"""
        // The Sink writes behind the commits: wait until both orders are in their last state.
        val rows = awaitSales(byStatus) { rows -> rows.map { it["status"].asString() } == listOf("CANCELLED", "SHIPPED") }
        rows.map { Triple(it["status"].asString(), it["orders"].asLong(), it["revenue"].decimalValue().stripTrailingZeros()) }
            .assert().containsExactly(
                Triple("CANCELLED", 1L, "6".toBigDecimal()),
                Triple("SHIPPED", 1L, "29".toBigDecimal()),
            )

        val byDay = """{"kind":"aggregate","filter":{"op":"EQ","field":"status","value":"SHIPPED"},""" +
            """"groupBy":[{"type":"DATE_HISTOGRAM","field":"commit_time","alias":"day","unit":"DAY"}],""" +
            """"metrics":[$REVENUE]}"""
        val days = sales(byDay)
        days.assert().describedAs(days.toString()).hasSize(1)
        days.single()["revenue"].decimalValue().assert().isEqualByComparingTo("29.00")
    }

    @Test
    @Order(4)
    fun `an ordering node killed mid-flow should lose no effect and repeat none once replaced`() {
        product("kite", "Wow kite", "20.00")
        receive("kite", 10)
        addItem("carol", "kite", price("kite"), 2)
        val partition = stack.orderPartition("carol-1")
        val owner = awaitOwner(partition)
        val probe = probesOn(partition).first()
        // The order process's subscriptions save their starting checkpoints in the partition as they start, 0 in a
        // partition without an order yet (G33), so the next owner does not start at a later end.
        stack.eventually("the order process's checkpoints in partition $partition") {
            stack.checkpointed(EshopStack.ORDERING_DATABASE, "OrderProcessSaga.", partition) == ORDER_PROCESS_HANDLERS
        }

        val checkedOut = checkout("carol", mapOf(WAIT to TO_ORDERING, WAIT_TIMEOUT to "30s"))
        checkedOut["status"].asString().assert().describedAs(checkedOut.toString()).isEqualTo("SUCCEEDED")
        checkNotNull(stack.ordering.remove(owner)).kill()
        val killedAt = System.nanoTime()
        // Killed before the order's stock was confirmed: no payment was requested.
        stack.payment("carol-1").assert().isNull()

        val replacement = stack.startOrdering(REPLACEMENT)
        stack.ordering[REPLACEMENT] = replacement
        val started = since(killedAt)
        // A node that does not hold a partition's lease runs its commands itself (D71), so the partition takes a
        // command again as soon as a node serves; its subscriptions, the order process's, wait for the lease.
        val answers = mutableListOf<String>()
        stack.eventually("a command of partition $partition taken", TAKEOVER_BOUND, { "Answers: ${answers.distinct()}" }) {
            submitProbe(replacement, probe, answers)
        }
        val commands = since(killedAt)
        var successor: String? = null
        stack.eventually("the lease of partition $partition taken over", TAKEOVER_BOUND) {
            successor = stack.orderingOwnerOf(partition)
            successor != null && successor != owner
        }
        val lease = since(killedAt)
        stack.eventually("carol-1 paid after the kill", TAKEOVER_BOUND, { "carol-1: ${stack.order("carol-1")}" }) {
            stack.orderStatus("carol-1") == "PAID"
        }
        val paid = since(killedAt)
        println(
            "[eshop-e2e] $owner killed: $REPLACEMENT served after ${started.toMillis()} ms, partition $partition took a " +
                "command after ${commands.toMillis()} ms, its lease ($successor) after ${lease.toMillis()} ms, and " +
                "carol-1 was paid after ${paid.toMillis()} ms.",
        )
        lease.assert().isLessThan(TAKEOVER_BOUND)

        ship("carol-1")
        stack.eventually("the reservation of carol-1 committed") {
            stack.stock("kite")["reservations"]["carol-1"] == null
        }
        // One order, one payment, the stock reserved and taken once.
        stack.stock("kite")["onHand"].asInt().assert().isEqualTo(8)
        stack.stock("kite")["available"].asInt().assert().isEqualTo(8)
        stack.events(stack.inventory, "/eshop-inventory/stock/kite").map(::simpleName)
            .assert().containsExactly("StockReceived", "StockReserved", "ReservationCommitted")
        orderEvents("carol-1").assert().containsExactly(
            "OrderSubmitted",
            "OrderAwaitingValidation",
            "OrderStockItemConfirmed",
            "OrderStockConfirmed",
            "ScheduleSet",
            "OrderPaid",
            "ScheduleCancelled",
            "OrderShipped",
        )
        stack.events(stack.payment, "/eshop-payment/payment/carol-1").map(::simpleName)
            .assert().containsExactly("PaymentRequested", "PaymentSucceeded")
        checkNotNull(stack.payment("carol-1"))["amount"].decimalValue().assert().isEqualByComparingTo("40.00")
        // The probe is cancelled as well: its product has no stock.
        stack.eventually("the probe $probe cancelled") { stack.orderStatus(probe) == "CANCELLED" }
    }

    /**
     * Submits the order [id] of a product the inventory has no stock of, which the order process then cancels;
     * whether [service] committed it. A try that failed late may have committed it, which the next one finds.
     */
    private fun submitProbe(service: EshopService, id: String, answers: MutableList<String> = mutableListOf()): Boolean {
        val submitted = service.post(
            "/eshop-ordering/order/$id/submit-order",
            """{"items":[{"productId":"none","unitPrice":1.00,"quantity":1}]}""",
            // Until it is committed only: the snapshot it would wait for by default is the lease owner's to write.
            mapOf(WAIT to "committed"),
        )
        answers += "${submitted.status} ${submitted.body.take(ANSWER)}"
        return submitted.status == OK || "wow.aggregate.exists" in submitted.body
    }

    private fun since(start: Long): Duration = Duration.ofNanos(System.nanoTime() - start)


    private fun product(id: String, name: String, price: String) {
        // An employee files the catalog. The brand and the category exist after the first product: their second
        // creation is refused, and ignored.
        stack.catalog.post("/eshop-catalog/brand/wow/create-brand", """{"name":"Wow"}""", EshopStack.EMPLOYEE)
        stack.catalog.post("/eshop-catalog/category/goods/create-category", """{"name":"Goods"}""", EshopStack.EMPLOYEE)
        val created = stack.catalog.post(
            "/eshop-catalog/product/$id/create-product",
            """{"name":"$name","price":$price,"brandId":"wow","categoryId":"goods"}""",
            EshopStack.EMPLOYEE,
        )
        created.status.assert().describedAs(created.body).isEqualTo(OK)
        val published = stack.catalog.post("/eshop-catalog/product/$id/publish-product", "{}", EshopStack.EMPLOYEE)
        published.status.assert().describedAs(published.body).isEqualTo(OK)
    }

    /** The price of [product], as a buyer reads it from the catalog: a product belongs to the tenant (D189). */
    private fun price(product: String): String {
        val state = checkNotNull(stack.state(stack.catalog, "/eshop-catalog/product/$product/state", EshopStack.buyer("dave")))
        state["status"].asString().assert().isEqualTo("PUBLISHED")
        return state["price"].decimalValue().toPlainString()
    }

    private fun receive(product: String, quantity: Int) {
        val received = stack.inventory.post("/eshop-inventory/stock/$product/receive-stock", """{"quantity":$quantity}""")
        received.status.assert().describedAs(received.body).isEqualTo(OK)
    }

    private fun addItem(buyer: String, product: String, price: String, quantity: Int) {
        val added = stack.basket.post(
            "/eshop-basket/basket/$buyer/add-basket-item",
            """{"productId":"$product","unitPrice":$price,"quantity":$quantity}""",
            EshopStack.buyer(buyer),
        )
        added.status.assert().describedAs(added.body).isEqualTo(OK)
    }

    private fun checkout(buyer: String, headers: Map<String, String> = emptyMap()): JsonNode {
        val checkedOut = stack.basket.post("/eshop-basket/basket/$buyer/checkout-basket", "{}", headers + EshopStack.buyer(buyer))
        checkedOut.status.assert().describedAs(checkedOut.body).isEqualTo(OK)
        return json.readTree(checkedOut.body)
    }

    private fun ship(order: String) {
        val shipped = stack.anyOrdering.post("/eshop-ordering/order/$order/ship-order", "{}")
        shipped.status.assert().describedAs(shipped.body).isEqualTo(OK)
    }

    private fun basketItems(buyer: String): List<JsonNode> =
        checkNotNull(stack.state(stack.basket, "/eshop-basket/basket/$buyer/state"))["items"].values().toList()

    private fun orderEvents(order: String): List<String> =
        stack.events(stack.anyOrdering, "/eshop-ordering/order/$order").map(::simpleName)

    private fun simpleName(event: String): String = event.substringAfterLast('.')

    /** The node holding [partition]'s lease, once one does. */
    private fun awaitOwner(partition: Int): String {
        var owner: String? = null
        stack.eventually("an owner of partition $partition") {
            owner = stack.orderingOwnerOf(partition)
            owner != null
        }
        return checkNotNull(owner)
    }

    /** Order ids on [partition], `probe-<n>`. */
    private fun probesOn(partition: Int): Sequence<String> =
        generateSequence(0) { it + 1 }.map { "probe-$it" }.filter { stack.orderPartition(it) == partition }

    private fun sales(query: String): List<JsonNode> {
        val answer = stack.anyOrdering.post(SALES, query)
        answer.status.assert().describedAs(answer.body).isEqualTo(OK)
        return json.readTree(answer.body).values().toList()
    }

    private fun awaitSales(query: String, condition: (List<JsonNode>) -> Boolean): List<JsonNode> {
        var last: Answer? = null
        var rows: List<JsonNode> = emptyList()
        stack.eventually("the sales dataset matched", detail = { "The last answer: ${last?.status} ${last?.body}" }) {
            // Until the Sink has created the view, the dataset's storage is unavailable.
            val answer = stack.anyOrdering.post(SALES, query)
            last = answer
            answer.status == OK && condition(json.readTree(answer.body).values().toList().also { rows = it })
        }
        return rows
    }

    private companion object {
        const val OK = 200
        const val ORDER_PROCESS_HANDLERS = 4
        const val WAIT = "Wow-Wait"
        const val WAIT_TIMEOUT = "Wow-Wait-Timeout"
        const val SALES = "/wow/dataset/eshop-ordering/sales/query"
        const val REVENUE = """{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"totalAmount"},"alias":"revenue"}"""
        const val REPLACEMENT = "ordering-3"
        const val TO_ORDERING = EshopStack.CHECKOUT_TO_ORDER
        const val FULL_CHAIN = EshopStack.CHECKOUT_TO_PAID
        const val ANSWER = 300
        val TAKEOVER_BOUND: Duration = Duration.ofSeconds(90)
    }
}
