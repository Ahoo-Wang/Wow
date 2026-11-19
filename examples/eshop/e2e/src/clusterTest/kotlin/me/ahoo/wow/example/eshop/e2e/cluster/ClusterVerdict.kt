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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.ORDER_NUMBER
import me.ahoo.wow.example.eshop.e2e.soak.Invariant
import me.ahoo.wow.example.eshop.e2e.soak.Invariants
import java.time.Duration
import kotlin.concurrent.thread

/**
 * The verdict (G.3): the soak's invariants over what the run did, read back through the gateway and from the stores
 * themselves ([StoreReads]: PostgreSQL's tables or MongoDB's collections), the same on both arms:
 *
 * - each event once: every aggregate's commits are its versions 1..n, each request id once; in the store, every
 *   aggregate's versions and every partition's sequences are taken once each (`log.contiguous`);
 * - stock conserved: each product's stock on hand is what it received less what the shipped orders took, nothing
 *   reserved once the orders settled;
 * - payment exactly once: an order paid has one payment, succeeded, of its total; a cancelled one none succeeded;
 * - every order settles; the failure ledgers are empty; the checkpoints never move backwards; the snapshots reach
 *   their logs' last versions.
 */
class ClusterVerdict(private val flows: ShopFlows, private val reads: StoreReads, private val log: (String) -> Unit) {
    private val soak = Invariants()
    val eventsOnce = soak.logContiguous
    val stockConserved = Invariant(
        "stock.conserved",
        "Each product's stock on hand is what it received less the quantities of the shipped orders, and nothing is " +
            "reserved or unavailable once every order settled.",
    )
    val paymentOnce = Invariant(
        "payment.exactly-once",
        "An order paid or shipped has one payment, succeeded once, of the order's total; a cancelled order has no " +
            "succeeded payment; no payment is requested or settled twice.",
    )
    val flowsSettled = soak.flowsComplete
    val ordersNumbered = Invariant(
        "orders.numbered",
        "Every order is numbered SO-<yyMMdd>-<sequence>, and the checkouts' answers name each number once: the ordering's " +
            "nodes, two or three while it scales, take one sequence's ranges from one store and never give one number " +
            "twice; every number answered is an order of the ordering.",
    )
    val noDuplicateEffects = soak.noDuplicateEffects
    val checkpoints = soak.checkpointsMonotonic
    val parked = soak.parkedEmpty
    val snapshots = soak.readModelsConverge

    val all: List<Invariant> =
        listOf(
            eventsOnce,
            noDuplicateEffects,
            stockConserved,
            paymentOnce,
            flowsSettled,
            ordersNumbered,
            checkpoints,
            parked,
            snapshots
        )

    @Volatile
    private var sampling = false
    private val lastCheckpoints = mutableMapOf<String, Long>()

    /** Samples every eShop database's checkpoints every few seconds until [stopSampling]. */
    fun startSampling() {
        sampling = true
        thread(name = "checkpoint-sampler", isDaemon = true) {
            while (sampling) {
                runCatching { sampleCheckpoints() }.onFailure { log("checkpoint sample failed: ${it.message}") }
                Thread.sleep(SAMPLE.toMillis())
            }
        }
    }

    fun stopSampling() {
        sampling = false
    }

    private fun sampleCheckpoints() {
        CONTEXTS.forEach { context ->
            reads.checkpoints(context).forEach { (key, position) ->
                val id = "$context|$key"
                checkpoints.check()
                synchronized(lastCheckpoints) {
                    val last = lastCheckpoints[id]
                    if (last != null && position < last) {
                        checkpoints.violate(mapOf("checkpoint" to id, "from" to last, "to" to position))
                    }
                    lastCheckpoints[id] = position
                }
            }
        }
    }

    /**
     * Waits, at most [deadline], until every checkout of the basket's log has its order in the ordering's log (the
     * checkout saga's call can still be on its way after the load stops), then until the order list (the ordering's
     * snapshot query, which lags behind its log) holds every order of that log and every listed order is settled
     * (paid, shipped or cancelled), and says how long each took: the run's lags at this verdict. An order whose state
     * read answers nothing stays open.
     */
    fun settle(deadline: Duration = SETTLE) {
        val started = System.nanoTime()
        var unordered: Set<String>
        do {
            unordered = checkoutsWithoutOrder()
            val done = unordered.isEmpty()
            if (!done) Thread.sleep(POLL.toMillis())
        } while (!done && System.nanoTime() - started < deadline.toNanos())
        val ordered = Duration.ofNanos(System.nanoTime() - started)
        if (unordered.isEmpty()) {
            log("every checkout had its order in the ordering's log after $ordered")
        } else {
            log("after $ordered, checkouts without an order: ${unordered.take(LISTED)}")
        }
        var unlisted: Set<String>
        var open: Map<String, String?>
        do {
            // The log first: every order it holds now must be listed.
            val logged = reads.aggregateIds("ordering", "order")
            val listed = flows.orders().toSet()
            unlisted = logged - listed
            open = listed.associateWith { runCatching { flows.orderStatus(it) }.getOrNull() }
                .filterValues { it !in SETTLED }
            val done = unlisted.isEmpty() && open.isEmpty()
            if (!done) Thread.sleep(POLL.toMillis())
        } while (!done && System.nanoTime() - started < deadline.toNanos())
        val waited = Duration.ofNanos(System.nanoTime() - started)
        if (unlisted.isEmpty() && open.isEmpty()) {
            log("the order list held the ordering's log, every order settled, after $waited in all")
        } else {
            log("after $waited, unlisted: ${unlisted.take(LISTED)}; open: ${open.entries.take(LISTED)}")
        }
    }

    /**
     * The basket's checkouts (`CheckoutBasket` commits) that no order of the ordering's log was caused by: a chain the
     * run started that has not reached the ordering yet.
     */
    private fun checkoutsWithoutOrder(): Set<String> =
        reads.commitsOf("basket", "basket", CHECKOUT) - reads.firstCauses("ordering", "order")

    /** The orders' numbers: well formed, each answered once, each an order of the ordering ([orders]). */
    private fun checkNumbers(orders: Set<String>) {
        val answered = flows.numbers.toList()
        (orders + answered).forEach { number ->
            ordersNumbered.check()
            if (!ORDER_NUMBER.matches(number)) ordersNumbered.violate(mapOf("number" to number, "problem" to "shape"))
        }
        answered.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (number, times) ->
            ordersNumbered.violate(mapOf("number" to number, "answered" to times))
        }
        (answered.toSet() - orders).forEach {
            ordersNumbered.violate(
                mapOf("number" to it, "problem" to "no such order")
            )
        }
    }

    fun judge() {
        stopSampling()
        // The run's orders have one origin, the basket's checkouts: an order's first commit names its own.
        val checkouts = reads.commitsOf("basket", "basket", CHECKOUT)
        val causes = reads.firstCauses("ordering", "order")
        val unordered = checkouts - causes
        check(unordered.isEmpty()) {
            "${unordered.size} checkouts of the basket's log have no order in the ordering's: ${unordered.take(LISTED)}"
        }
        val otherOrigins = causes - checkouts
        val logged = reads.aggregateIds("ordering", "order")
        val listed = flows.orders()
        val unlisted = logged - listed.toSet()
        check(unlisted.isEmpty()) {
            "the order list lacks ${unlisted.size} orders of the ordering's log: ${unlisted.take(LISTED)}"
        }
        val orders = listed.associateWith { id ->
            checkNotNull(flows.order(id)) { "the state read of $id answered nothing" }
        }
        log(
            "judging ${orders.size} orders listed, ${logged.size} in the ordering's log, from ${checkouts.size} " +
                "checkouts in the basket's log (${flows.checkouts.values.sumOf { it.get() }} the run made); " +
                "orders of another origin: ${otherOrigins.size} ${otherOrigins.take(LISTED)}"
        )
        checkNumbers(orders.keys)
        orders.forEach { (id, order) ->
            val status = order["status"].asString()
            flowsSettled.check()
            if (status !in SETTLED) flowsSettled.violate(mapOf("order" to id, "status" to status))
            checkTrace("order", "/eshop-ordering/order/$id")
            checkPayment(id, status, order)
        }
        checkStock(orders)
        checkLogs()
    }

    private fun checkTrace(kind: String, path: String) {
        val commits = flows.trace(path)
        eventsOnce.check(commits.size.toLong())
        val versions = commits.map { it.version }
        if (versions != (1..commits.size).toList()) {
            eventsOnce.violate(mapOf("aggregate" to path, "versions" to versions))
        }
        val repeated = commits.groupingBy { it.requestId }.eachCount().filterValues { it > 1 }
        noDuplicateEffects.check()
        if (repeated.isNotEmpty()) {
            noDuplicateEffects.violate(
                mapOf("aggregate" to path, "kind" to kind, "requests" to repeated)
            )
        }
    }

    private fun checkPayment(id: String, status: String, order: tools.jackson.databind.JsonNode) {
        paymentOnce.check()
        val payment = flows.payment(id)
        val events = if (payment == null) {
            emptyList()
        } else {
            flows.trace(
                "/eshop-payment/payment/$id"
            ).flatMap { it.events }
        }
        val requested = events.count { it == "PaymentRequested" }
        val succeeded = events.count { it == "PaymentSucceeded" }
        val failed = events.count { it == "PaymentFailed" }
        val evidence = mapOf("order" to id, "status" to status, "events" to events)
        when {
            requested > 1 || succeeded + failed > 1 -> paymentOnce.violate(
                evidence + ("reason" to "requested or settled twice")
            )
            status in PAID && (succeeded != 1 || payment?.get("status")?.asString() != "SUCCEEDED") ->
                paymentOnce.violate(evidence + ("reason" to "paid without one succeeded payment"))
            // A paid order's payment is there: the branch above takes one without a succeeded payment.
            status in PAID && payment?.get("amount")?.decimalValue()?.compareTo(ShopFlows.total(order)) != 0 ->
                paymentOnce.violate(
                    evidence +
                        ("reason" to "amount ${payment?.get("amount")} is not the total ${ShopFlows.total(order)}")
                )
            status == "CANCELLED" && succeeded > 0 -> paymentOnce.violate(evidence + ("reason" to "cancelled but paid"))
        }
        if (payment != null) checkTrace("payment", "/eshop-payment/payment/$id")
        val paid = flows.trace("/eshop-ordering/order/$id").flatMap { it.events }.count { it == "OrderPaid" }
        noDuplicateEffects.check()
        if (paid > 1) noDuplicateEffects.violate(mapOf("order" to id, "orderPaid" to paid))
    }

    /**
     * Each product's stock: on hand is what it received less what the shipped orders took; reserved is exactly what the
     * paid orders not shipped yet hold (a reservation is committed when its order ships), each by its order id; and
     * available is on hand less reserved. A cancelled order holds nothing.
     */
    private fun checkStock(orders: Map<String, tools.jackson.databind.JsonNode>) {
        val taken = mutableMapOf<String, Int>()
        val held = mutableMapOf<String, MutableMap<String, Int>>()
        orders.forEach { (id, order) ->
            val status = order["status"].asString()
            order["items"].values().forEach { item ->
                val product = item["productId"].asString()
                val quantity = item["quantity"].asInt()
                when {
                    status == "SHIPPED" -> taken.merge(product, quantity, Int::plus)
                    status == "PAID" -> held.getOrPut(product) { mutableMapOf() }.merge(id, quantity, Int::plus)
                }
            }
        }
        flows.received.forEach { (product, received) ->
            stockConserved.check()
            val stock = flows.stock(product)
            val onHand = stock["onHand"].asInt()
            val available = stock["available"].asInt()
            val reserved = stock["reservations"]?.properties()?.associate { (id, quantity) -> id to quantity.asInt() }.orEmpty()
            val expectedHeld = held[product].orEmpty()
            val expectedOnHand = received - (taken[product] ?: 0)
            if (onHand != expectedOnHand || reserved != expectedHeld || available != onHand - reserved.values.sum()) {
                stockConserved.violate(
                    mapOf(
                        "product" to product,
                        "received" to received,
                        "shipped" to (taken[product] ?: 0),
                        "onHand" to onHand,
                        "available" to available,
                        "unexpectedReservations" to (reserved - expectedHeld.keys).toString().take(EVIDENCE),
                        "missingReservations" to (expectedHeld - reserved.keys).toString().take(EVIDENCE),
                    ),
                )
            }
        }
    }

    private fun checkLogs() {
        CONTEXTS.forEach { context ->
            eventsOnce.check()
            reads.logViolations(context).forEach(eventsOnce::violate)
            snapshots.check()
            reads.snapshotsBehind(context).forEach(snapshots::violate)
            parked.check()
            reads.parked(context).forEach(parked::violate)
        }
    }

    companion object {
        const val EVIDENCE = 500
        const val LISTED = 20
        const val CHECKOUT = "eshop-basket.basket.CheckoutBasket"
        val CONTEXTS = listOf("catalog", "basket", "ordering", "inventory", "payment")
        val SETTLED = setOf("PAID", "SHIPPED", "CANCELLED")
        val PAID = setOf("PAID", "SHIPPED")
        val SETTLE: Duration = Duration.ofMinutes(5)
        val SAMPLE: Duration = Duration.ofSeconds(10)
        val POLL: Duration = Duration.ofSeconds(2)
    }
}
