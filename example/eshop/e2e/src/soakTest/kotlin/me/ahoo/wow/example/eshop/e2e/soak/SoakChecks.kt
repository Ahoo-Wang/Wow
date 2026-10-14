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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The soak's checks: [sample]s while the soak runs (the checkpoints, the parked ledger and, every few samples, the
 * logs' contiguity), the [settle]ment once the load and the faults stop, and the [finalChecks].
 */
class SoakChecks(
    private val stack: SoakStack,
    private val config: SoakConfig,
    private val faults: FaultInjector,
    private val evidence: File,
    private val log: (String) -> Unit,
) {
    val invariants = Invariants()
    private val running = AtomicBoolean(true)
    private var sampler: Thread? = null
    private val checkpoints = HashMap<String, Long>()

    /** Since when each lease (`context|group|partition`) has had no live owner, and whether its stall is reported. */
    private val unowned = HashMap<String, Long>()
    private val stalled = HashSet<String>()

    /** Every stall of a partition without an owner: when it began, when it ended, the thread dumps taken. */
    val stalls = mutableListOf<MutableMap<String, Any?>>()

    /** Every parked entry seen, by database and key, with when it was first and last seen. */
    val parkedSeen = LinkedHashMap<String, MutableMap<String, Any?>>()
    var samples = 0
        private set
    var failedSamples = 0
        private set

    /** What the settlement found: how long it took, and what had not settled by its deadline. */
    val settlement = linkedMapOf<String, Any?>()

    fun startSampling() {
        sampler = thread(name = "soak-sampler", isDaemon = true) {
            while (running.get()) {
                sample()
                Thread.sleep(config.sampleInterval.toMillis())
            }
        }
    }

    fun stopSampling() {
        running.set(false)
        sampler?.join()
        sample()
    }

    /** One sample of every database; one that fails (PostgreSQL paused or restarting) is counted and skipped. */
    @Synchronized
    fun sample() {
        samples++
        SoakConfig.CONTEXTS.forEach { context ->
            try {
                stack.connect(SoakStack.DATABASES.getValue(context)).use { connection ->
                    val database = SoakDatabase(connection, context)
                    sampleCheckpoints(database)
                    sampleLeases(database)
                    sampleParked(database)
                    if (samples % CONTIGUITY_EVERY == 1) checkContiguity(database)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                failedSamples++
                if (failedSamples % LOG_FAILED_EVERY == 1) log("sample of $context failed: $failure")
            }
        }
    }

    private fun sampleCheckpoints(database: SoakDatabase) {
        val now = System.currentTimeMillis()
        database.checkpoints().forEach { (key, position) ->
            val id = "${database.context}|$key"
            invariants.checkpointsMonotonic.check()
            val before = checkpoints.put(id, position)
            if (before != null && position < before) {
                invariants.checkpointsMonotonic.violate(
                    linkedMapOf(
                        "checkpoint" to id,
                        "before" to before,
                        "after" to position,
                        "at" to now,
                        "fault" to faultAround(now),
                    ),
                )
                log("checkpoint $id moved back from $before to $position")
            }
        }
    }

    /**
     * Tracks each lease without a live owner; one still without after [OWNERLESS_LIMIT] is a stall, reported once with
     * a thread dump of the context's nodes, and closed when the partition has an owner again.
     */
    private fun sampleLeases(database: SoakDatabase) {
        val now = System.currentTimeMillis()
        database.leases().forEach { (key, lease) ->
            val id = "${database.context}|$key"
            val (owner, expiresAt) = lease
            if (expiresAt > now) {
                unowned.remove(id)
                if (stalled.remove(id)) {
                    stalls.lastOrNull { it["lease"] == id }?.set("ended", now)
                    stalls.lastOrNull { it["lease"] == id }?.set("owner", owner)
                    log("lease $id has an owner again: $owner")
                }
                return@forEach
            }
            invariants.partitionsOwned.check()
            val since = unowned.getOrPut(id) { now }
            if (now - since > OWNERLESS_LIMIT.toMillis() && stalled.add(id)) {
                val dumps = stack.threadDumps(database.context, evidence, "${id.replace('|', '_').replace('/', '_').replace(':', '_')}-$now")
                val stall = linkedMapOf<String, Any?>(
                    "lease" to id,
                    "lastOwner" to owner,
                    "unownedSince" to since,
                    "reportedAt" to now,
                    "ended" to null,
                    "fault" to faultAround(since),
                    "nodes" to stack.nodes(database.context).map { it.id },
                    "threadDumps" to dumps.map { it.name },
                )
                stalls += stall
                invariants.partitionsOwned.violate(stall)
                log("lease $id has had no owner since $since (last $owner); thread dumps ${dumps.map { it.name }}")
            }
        }
    }

    private fun sampleParked(database: SoakDatabase) {
        val now = System.currentTimeMillis()
        val current = database.parked()
        current.forEach { (key, entry) ->
            val id = "${database.context}|$key"
            val seen = parkedSeen.getOrPut(id) {
                log("parked in ${database.context}: $key: ${entry["reason"]}")
                (entry + mapOf("firstSeen" to now, "fault" to faultAround(entry["failedAt"] as Long))).toMutableMap()
            }
            seen["lastSeen"] = now
            seen["reason"] = entry["reason"]
            seen["resolved"] = false
        }
        parkedSeen.filterKeys { it.startsWith("${database.context}|") && it.removePrefix("${database.context}|") !in current }
            .values.forEach { it["resolved"] = true }
    }

    private fun checkContiguity(database: SoakDatabase) {
        val tables = database.logTables().values
        invariants.logContiguous.check()
        database.partitionGaps(tables).forEach { gap ->
            invariants.logContiguous.violate(gap + ("at" to System.currentTimeMillis()))
            log("log gap: $gap")
        }
        tables.forEach { table ->
            database.versionGaps(table).forEach { gap ->
                invariants.logContiguous.violate(gap + ("at" to System.currentTimeMillis()))
                log("version gap: $gap")
            }
        }
    }

    /**
     * Waits until every checked-out order has settled, shipping each paid one the client did not ship (its checkout
     * did not see the payment), for at most [SoakConfig.quiescence]. Settled: shipped, or cancelled.
     */
    fun settle(load: SoakLoad) {
        val begun = System.nanoTime()
        val shipping = mutableSetOf<String>()
        val byOrder = load.orders.associateBy { it.orderId }
        var pending: List<String> = emptyList()
        while (System.nanoTime() - begun < config.quiescence.toNanos()) {
            val statuses = runCatching { orderStatuses() }.getOrNull()
            val checkedOut = runCatching { checkedOutOrders() }.getOrNull()
            if (statuses != null && checkedOut != null) {
                checkedOut.filter { statuses[it] == "PAID" && it !in shipping }.forEach { id ->
                    byOrder[id]?.takeIf { it.scenario == Scenario.PAID }?.let { order ->
                        shipping += id
                        load.ship(order)
                    }
                }
                pending = checkedOut.filter { statuses[it] != "SHIPPED" && statuses[it] != "CANCELLED" }
                if (pending.isEmpty()) break
            }
            Thread.sleep(SETTLE_POLL.toMillis())
        }
        settlement["millis"] = Duration.ofNanos(System.nanoTime() - begun).toMillis()
        settlement["shippedWhileSettling"] = shipping.size
        settlement["unsettled"] = pending.size
        settlement["unsettledOrders"] = pending.take(MAX_LISTED)
        log("settled in ${settlement["millis"]} ms, ${shipping.size} shipped while settling, ${pending.size} unsettled")
        // The snapshots trail the commits: give the last ones the time to be written.
        awaitSnapshots()
    }

    private fun orderStatuses(): Map<String, String?> = stack.connect(SoakStack.DATABASES.getValue("ordering")).use {
        SoakDatabase(it, "ordering").snapshotStates(ORDER_TABLE).mapValues { (_, state) -> state["status"]?.asString() }
    }

    private fun checkedOutOrders(): List<String> = stack.connect(SoakStack.DATABASES.getValue("basket")).use {
        SoakDatabase(it, "basket").requestIds(BASKET_TABLE, "%:checkout").keys.map { id -> id.removeSuffix(":checkout") + "-1" }
    }

    private fun awaitSnapshots() {
        val until = System.nanoTime() + SNAPSHOT_DEADLINE.toNanos()
        while (System.nanoTime() < until) {
            val behind = SoakConfig.CONTEXTS.sumOf { context ->
                stack.connect(SoakStack.DATABASES.getValue(context)).use { connection ->
                    val database = SoakDatabase(connection, context)
                    database.logTables().values.sumOf { database.snapshotsBehind(it).size }
                }
            }
            if (behind == 0) return
            Thread.sleep(SETTLE_POLL.toMillis())
        }
    }

    /** The checks at the end, once settled: every invariant over the whole run. */
    fun finalChecks(load: SoakLoad) {
        val orders = load.orders.toList()
        checkWaits(orders)
        val data = SoakConfig.CONTEXTS.associateWith { context ->
            stack.connect(SoakStack.DATABASES.getValue(context)).use { connection ->
                val database = SoakDatabase(connection, context)
                checkContiguity(database)
                sampleParked(database)
                val tables = database.logTables()
                tables.values.forEach { table ->
                    invariants.readModelsConverge.check(database.aggregateCount(table))
                    database.snapshotsBehind(table).forEach { invariants.readModelsConverge.violate(it) }
                }
                ContextData(
                    tables.mapValues { (_, table) -> database.commits(table) },
                    tables.mapValues { (_, table) -> database.snapshotStates(table) },
                )
            }
        }
        checkParked()
        checkExactlyOnce(orders, data)
        checkFlows(orders, data)
        checkReplays(data)
    }

    private fun checkWaits(orders: List<SoakOrder>) {
        orders.flatMap { order -> order.requests.toList() }.forEach { request ->
            request.attempts.toList().forEach { attempt ->
                invariants.waitsAnswered.check()
                val hung = attempt.outcome == Outcome.HUNG
                if (hung || (attempt.outcome == Outcome.TIMEOUT && attempt.hop == null)) {
                    invariants.waitsAnswered.violate(
                        linkedMapOf(
                            "requestId" to request.requestId,
                            "outcome" to attempt.outcome.name,
                            "node" to attempt.node,
                            "at" to attempt.at,
                            "millis" to attempt.millis,
                            "detail" to attempt.detail,
                            "fault" to faultAround(attempt.at),
                        ),
                    )
                }
            }
        }
    }

    private fun checkParked() {
        parkedSeen.values.forEach { entry ->
            invariants.parkedEmpty.check()
            if (entry["resolved"] != true) invariants.parkedEmpty.violate(entry)
        }
    }

    /** Commands committed exactly once by request id; the log holds no request id of the soak no client sent. */
    private fun checkExactlyOnce(orders: List<SoakOrder>, data: Map<String, ContextData>) {
        val logged = mapOf(
            "basket" to requestCounts(data.getValue("basket")),
            "ordering" to requestCounts(data.getValue("ordering")),
        )
        val sent = mutableSetOf<String>()
        orders.flatMap { it.requests.toList() }.forEach { request ->
            sent += request.requestId
            val count = logged[request.context]?.get(request.requestId) ?: 0
            invariants.exactlyOnce.check()
            val answeredCommitted = request.committed
            if (count > 1 || (answeredCommitted && count != 1)) {
                invariants.exactlyOnce.violate(
                    linkedMapOf(
                        "requestId" to request.requestId,
                        "context" to request.context,
                        "inLog" to count,
                        "answers" to request.attempts.map { "${it.outcome}${it.code?.let { code -> " $code" } ?: ""}" },
                    ),
                )
            }
        }
        logged.forEach { (context, counts) ->
            counts.keys.filter { it.startsWith("c") && ':' in it && it !in sent }.forEach { requestId ->
                invariants.exactlyOnce.violate(linkedMapOf("requestId" to requestId, "context" to context, "sent" to false))
            }
        }
    }

    private fun requestCounts(data: ContextData): Map<String, Int> =
        data.commits.values.flatMap { it.values.flatten() }.groupingBy { it.requestId }.eachCount()

    /** Each order's events, its payment's and its stock's, as its scenario calls for; the stock adds up. */
    private fun checkFlows(orders: List<SoakOrder>, data: Map<String, ContextData>) {
        val baskets = data.getValue("basket").commits["basket"].orEmpty()
        val ordersLog = data.getValue("ordering").commits["order"].orEmpty()
        val orderStates = data.getValue("ordering").snapshots["order"].orEmpty()
        val payments = data.getValue("payment").commits["payment"].orEmpty()
        val stocks = data.getValue("inventory").commits["stock"].orEmpty()
        val stockByOrder = stocks.values.flatten().flatMap { commit ->
            commit.events.mapNotNull { event ->
                event.payload["orderId"]?.asString()?.let { it to "${commit.aggregateId}:${event.name}" }
            }
        }.groupBy({ it.first }, { it.second })
        var shippedA = 0
        orders.forEach { order ->
            invariants.noDuplicateEffects.check()
            val basket = baskets[order.buyer].orEmpty()
            val checkedOut = basket.any { commit -> commit.events.any { it.name == "BasketCheckedOut" } }
            if (basket.isNotEmpty()) {
                val expectedBasket = listOf("BasketItemAdded") + if (checkedOut) listOf("BasketCheckedOut") else emptyList()
                expect(order, "basket", names(basket), expectedBasket)
            }
            val events = names(ordersLog[order.orderId].orEmpty())
            val payment = names(payments[order.orderId].orEmpty())
            val stock = stockByOrder[order.orderId].orEmpty()
            if (!checkedOut) {
                expect(order, "order", events, emptyList())
                return@forEach
            }
            invariants.flowsComplete.check()
            val status = orderStates[order.orderId]?.get("status")?.asString()
            val (expectedOrder, expectedPayment, expectedStock) = expected(order)
            if (status != expectedOrder.second) {
                invariants.flowsComplete.violate(
                    linkedMapOf(
                        "order" to order.orderId,
                        "scenario" to order.scenario.name,
                        "status" to status,
                        "expected" to expectedOrder.second,
                        "events" to events,
                        "payment" to payment,
                        "stock" to stock,
                    ),
                )
            }
            expect(order, "order", events, expectedOrder.first)
            expect(order, "payment", payment, expectedPayment)
            expect(order, "stock", stock, expectedStock)
            if (order.scenario == Scenario.PAID && "OrderShipped" in events) shippedA += order.quantity
        }
        checkStock(data.getValue("inventory"), shippedA)
    }

    private fun expected(order: SoakOrder): Triple<Pair<List<String>, String>, List<String>, List<String>> {
        val validated = listOf("OrderSubmitted", "OrderAwaitingValidation")
        val confirmed = validated + listOf("OrderStockItemConfirmed", "OrderStockConfirmed")
        val product = order.scenario.product
        return when (order.scenario) {
            Scenario.PAID -> Triple(
                confirmed + listOf("OrderPaid", "OrderShipped") to "SHIPPED",
                listOf("PaymentRequested", "PaymentSucceeded"),
                listOf("$product:StockReserved", "$product:ReservationCommitted"),
            )

            Scenario.SHORT_STOCK -> Triple(validated + "OrderCancelled" to "CANCELLED", emptyList(), emptyList())
            Scenario.DECLINED -> Triple(
                confirmed + "OrderCancelled" to "CANCELLED",
                listOf("PaymentRequested", "PaymentFailed"),
                listOf("$product:StockReserved", "$product:ReservationReleased"),
            )
        }
    }

    private fun expect(order: SoakOrder, what: String, actual: List<String>, expected: List<String>) {
        if (actual == expected) return
        val repeated = actual.size > expected.size && actual.toSet() == expected.toSet()
        // A flow that did not settle shows as a prefix: flows.complete reports it; anything else is a wrong effect.
        val prefix = actual.size < expected.size && expected.take(actual.size) == actual
        if (prefix) return
        invariants.noDuplicateEffects.violate(
            linkedMapOf(
                "order" to order.orderId,
                "scenario" to order.scenario.name,
                "aggregate" to what,
                "events" to actual,
                "expected" to expected,
                "repeated" to repeated,
                "requests" to order.requests.map { request ->
                    "${request.requestId}: ${request.attempts.joinToString { "${it.outcome}@${it.at}" }}"
                },
            ),
        )
    }

    private fun checkStock(inventory: ContextData, shippedA: Int) {
        val states = inventory.snapshots["stock"].orEmpty()
        val expectations = mapOf(
            SoakLoad.PRODUCT_A to SEED_STOCK - shippedA,
            SoakLoad.PRODUCT_B to SEED_STOCK,
            SoakLoad.PRODUCT_SHORT to SHORT_STOCK,
        )
        expectations.forEach { (product, onHand) ->
            invariants.flowsComplete.check()
            val state = states[product]
            val actualOnHand = state?.get("onHand")?.asInt()
            val available = state?.get("available")?.asInt()
            val reservations = state?.get("reservations")?.let { if (it.isObject) it.size() else it.size() } ?: 0
            if (actualOnHand != onHand || available != onHand || reservations != 0) {
                invariants.flowsComplete.violate(
                    linkedMapOf(
                        "stock" to product,
                        "onHand" to actualOnHand,
                        "available" to available,
                        "reservations" to state?.get("reservations")?.toString()?.take(MAX_TEXT),
                        "expectedOnHand" to onHand,
                    ),
                )
            }
        }
    }

    /** A sample of each type's snapshots, and every stock, compared with a replay of their log through the trace. */
    private fun checkReplays(data: Map<String, ContextData>) {
        val random = Random(config.seed)
        data.forEach { (context, contextData) ->
            contextData.snapshots.forEach { (aggregate, snapshots) ->
                val ids = snapshots.keys.sorted().let { ids ->
                    if (aggregate == "stock" || ids.size <= config.replaySample) ids else ids.shuffled(random).take(config.replaySample)
                }
                ids.forEach { id -> compareReplay(context, aggregate, id, snapshots.getValue(id)) }
            }
        }
    }

    private fun compareReplay(context: String, aggregate: String, id: String, snapshot: JsonNode) {
        invariants.readModelsConverge.check()
        val node = stack.nodes(context).firstOrNull() ?: return
        val path = "/eshop-$context/$aggregate/$id/state/trace"
        var from = 1
        var last: JsonNode? = null
        while (true) {
            val answer = runCatching { node.service.get("$path?from=$from&limit=$TRACE_PAGE") }.getOrNull()
            if (answer == null || answer.status != OK) {
                invariants.readModelsConverge.violate(
                    linkedMapOf("aggregate" to "$context.$aggregate", "id" to id, "trace" to "${answer?.status} ${answer?.body?.take(MAX_TEXT)}"),
                )
                return
            }
            val commits = json.readTree(answer.body)["commits"].values().toList()
            last = commits.lastOrNull() ?: last
            if (commits.size < TRACE_PAGE) break
            from = commits.last()["version"].asInt() + 1
        }
        val replayed = last?.get("state")
        if (replayed != snapshot) {
            invariants.readModelsConverge.violate(
                linkedMapOf(
                    "aggregate" to "$context.$aggregate",
                    "id" to id,
                    "replayedVersion" to last?.get("version")?.asInt(),
                    "replayed" to replayed?.toString()?.take(MAX_TEXT),
                    "snapshot" to snapshot.toString().take(MAX_TEXT),
                ),
            )
        }
    }

    private fun names(commits: List<SoakDatabase.Commit>): List<String> = commits.flatMap { commit -> commit.events.map { it.name } }

    /** The fault whose window holds [at] (from its start to a minute after its recovery), if any. */
    fun faultAround(at: Long): String? = faults.timeline.lastOrNull { event ->
        event.began <= at && at <= (event.recovered ?: event.injected ?: Long.MAX_VALUE) + FAULT_TAIL.toMillis()
    }?.let { "${it.kind}@${it.began}" }

    /** A context's commits and snapshots, by aggregate type. */
    class ContextData(
        val commits: Map<String, Map<String, List<SoakDatabase.Commit>>>,
        val snapshots: Map<String, Map<String, JsonNode>>,
    )

    companion object {
        const val SEED_STOCK = 1_000_000
        const val SHORT_STOCK = 1
        private const val ORDER_TABLE = "wow_log__eshop_ordering__order"
        private const val BASKET_TABLE = "wow_log__eshop_basket__basket"
        private const val CONTIGUITY_EVERY = 6
        private const val LOG_FAILED_EVERY = 10
        private const val MAX_LISTED = 50
        private const val MAX_TEXT = 2000
        private const val TRACE_PAGE = 1000
        private const val OK = 200
        private val SETTLE_POLL: Duration = Duration.ofSeconds(5)
        private val SNAPSHOT_DEADLINE: Duration = Duration.ofSeconds(60)
        private val FAULT_TAIL: Duration = Duration.ofMinutes(1)
        private val OWNERLESS_LIMIT: Duration = Duration.ofMinutes(1)
    }
}
