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

package me.ahoo.wow.rehearsal.driver

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.system.exitProcess

/** An order of the rehearsal: its id, its tenant and its owner, as both versions address it. */
data class OrderRef(val id: String, val tenant: String, val owner: String)

/** Where the driver left an order: its status and its last version, one line of the order book. */
data class BookedOrder(val ref: OrderRef, val status: String, val version: Int, val total: Int) {
    fun line(): String = listOf(ref.id, ref.tenant, ref.owner, status, version, total).joinToString("\t")

    companion object {
        fun parse(line: String): BookedOrder = line.split('\t').let {
            BookedOrder(OrderRef(it[0], it[1], it[2]), it[3], it[4].toInt(), it[5].toInt())
        }
    }
}

/**
 * The load driver of the blue-green rehearsal (roadmap 8c.1), against the 9.x example-server or the rehearsal host:
 *
 * ```text
 * driver seed    --version=9x|v10 --base=<url> --book=<file> --orders=N [--carts=M] [--tenants=T] [--concurrency=C]
 * driver traffic --version=9x|v10 --base=<url> --book=<file> --seconds=S [--touched=<file>] [--create=K] [--concurrency=C]
 * driver await   --base=<v10 url> --touched=<file> [--timeout=S]
 * driver states  --version=9x|v10 --base=<url> --book=<file> --out=<file> [--sample=N]
 * driver compare <states a> <states b>
 * driver views …  (the view store's part, see ViewsDriver)
 * ```
 *
 * - `seed` creates N orders, each taken through a lifecycle drawn from its index (some only created, some with a
 *   changed address, some paid, shipped and received), and M carts; the order book records where each order stands.
 * - `traffic` moves existing orders on by one step at a time for S seconds, and adds to carts; with `--create`, it
 *   creates K new orders instead, and nothing else. It records each order it wrote and its version in the touched file.
 * - `await` waits until the rehearsal host holds every touched order at its version: the tail's lag behind 9.x.
 * - `states` reads every N-th order's state; `compare` compares two such files (of orders, or of views), numbers by
 *   value, and exits with 3 when one differs.
 *
 * Each command prints one JSON line of its numbers, which rehearse.sh copies into the report.
 */
class RehearsalDriver(private val args: List<String>) {
    private val options: Map<String, String> = args.filter { it.startsWith("--") }.associate { option ->
        option.removePrefix("--").split('=', limit = 2).let { it[0] to it.getOrElse(1) { "true" } }
    }
    private val concurrency = options["concurrency"]?.toInt() ?: DEFAULT_CONCURRENCY

    fun run(): Int = when (args.firstOrNull()) {
        "seed" -> seed()
        "traffic" -> traffic()
        "await" -> await()
        "states" -> states()
        "compare" -> compare(File(args[1]), File(args[2]))
        "views" -> ViewsDriver(args.drop(1), concurrency).run()
        else -> {
            System.err.println("Usage: driver seed|traffic|await|states|compare|views … (see RehearsalDriver)")
            USAGE
        }
    }

    private fun required(name: String): String = options[name] ?: throw IllegalArgumentException("--$name is required.")

    private fun endpoints(): Endpoints = Endpoints.of(required("version"), required("base"))

    private fun seed(): Int {
        val endpoints = endpoints()
        val orders = required("orders").toInt()
        val carts = options["carts"]?.toInt() ?: (orders / 2)
        val tenants = options["tenants"]?.toInt() ?: DEFAULT_TENANTS
        val stats = Stats()
        val book = ConcurrentHashMap<String, BookedOrder>()
        parallel(0 until orders) { index ->
            val random = Random(index)
            val ref =
                OrderRef("o-%08d".format(index), "t${index % tenants}", "c-%06d".format(index % carts.coerceAtLeast(1)))
            book[ref.id] = lifecycle(endpoints, ref, random, stats)
        }
        parallel(0 until carts) { index ->
            val customer = "c-%06d".format(index)
            repeat(1 + index % 3) { line ->
                stats.count(
                    endpoints.addCartItem(
                        customer,
                        """{"productId":"p${(index + line) % PRODUCTS}","quantity":${1 + line}}"""
                    )
                )
            }
        }
        File(
            required("book")
        ).writeText(book.values.sortedBy { it.ref.id }.joinToString("\n", postfix = "\n") { it.line() })
        println(stats.json("seed", "\"orders\":$orders,\"carts\":$carts,\"tenants\":$tenants"))
        return if (stats.failed.get() == 0L) OK else FAILED
    }

    /** Creates [ref] and takes it as far through its lifecycle as [random] draws. */
    private fun lifecycle(endpoints: Endpoints, ref: OrderRef, random: Random, stats: Stats): BookedOrder {
        val quantity = 1 + random.nextInt(MAX_QUANTITY)
        val total = quantity * PRICE
        var booked = BookedOrder(ref, "NONE", 0, total)
        fun step(status: String, outcome: Outcome): Boolean {
            stats.count(outcome)
            if (outcome.ok) {
                booked = booked.copy(status = status, version = outcome.version ?: (booked.version + 1))
            }
            return outcome.ok
        }
        val created = step("CREATED", endpoints.createOrder(ref, createBody(random, quantity)))
        if (!created) return booked
        if (random.nextInt(PERCENT) < ADDRESS_CHANGED && !step("CREATED", endpoints.changeAddress(ref, addressBody(random)))) {
            return booked
        }
        if (random.nextInt(PERCENT) >= PAID || !step("PAID", endpoints.payOrder(ref, payBody(ref, total)))) return booked
        if (random.nextInt(PERCENT) >= SHIPPED || !step("SHIPPED", endpoints.shipOrder(ref))) return booked
        if (random.nextInt(PERCENT) < RECEIVED) step("RECEIVED", endpoints.receiptOrder(ref))
        return booked
    }

    private fun traffic(): Int {
        val bookFile = File(required("book"))
        val run = Traffic(
            endpoints(),
            bookFile.readLines().filter { it.isNotBlank() }.map(BookedOrder::parse),
            options["create"]?.toLong() ?: 0,
        )
        val deadline = System.nanoTime() + required("seconds").toLong() * NANOS_PER_SECOND
        parallel(0 until concurrency) { worker ->
            val random = Random(worker * WORKER_SEED + System.nanoTime())
            while (System.nanoTime() < deadline && run.next(random)) {
                // Each call sends one command.
            }
        }
        bookFile.writeText(run.book.values.sortedBy { it.ref.id }.joinToString("\n", postfix = "\n") { it.line() })
        options["touched"]?.let { path ->
            File(
                path
            ).writeText(run.touched.values.sortedBy { it.ref.id }.joinToString("\n", postfix = "\n") { it.line() })
        }
        println(run.stats.json("traffic", "\"touched\":${run.touched.size},\"created\":${run.createdCount()}"))
        return OK
    }

    /**
     * One run of `traffic` over [orders]: either it creates [toCreate] orders and nothing else, or it moves orders on
     * and fills carts.
     */
    private inner class Traffic(val endpoints: Endpoints, orders: List<BookedOrder>, val toCreate: Long) {
        val book = ConcurrentHashMap(orders.associateBy { it.ref.id })
        val touched = ConcurrentHashMap<String, BookedOrder>()
        val stats = Stats()
        private val created = AtomicLong()
        private val ids = book.keys.sorted()

        fun createdCount(): Long = minOf(created.get(), toCreate)

        /** Sends one command; false once there is nothing left to send. */
        fun next(random: Random): Boolean = when {
            toCreate > 0 -> created.incrementAndGet().let { number ->
                if (number <= toCreate) {
                    create(
                        random,
                        number
                    )
                } else {
                    false
                }
            }
            random.nextInt(PERCENT) < CART_SHARE -> addToCart(random)
            else -> advance(random)
        }

        private fun create(random: Random, number: Long): Boolean {
            val ref = OrderRef("n-%s-%06d".format(endpoints.javaClass.simpleName.lowercase(), number), "t0", "c-000000")
            val quantity = 1 + random.nextInt(MAX_QUANTITY)
            val outcome = endpoints.createOrder(ref, createBody(random, quantity))
            stats.count(outcome)
            if (outcome.ok) booked(BookedOrder(ref, "CREATED", outcome.version ?: 1, quantity * PRICE))
            return true
        }

        private fun addToCart(random: Random): Boolean {
            val customer = "c-%06d".format(random.nextInt(ids.size / 2 + 1))
            stats.count(
                endpoints.addCartItem(customer, """{"productId":"p${random.nextInt(PRODUCTS)}","quantity":1}""")
            )
            return true
        }

        /** Moves a random order on by one step; one already received is left as it is. */
        private fun advance(random: Random): Boolean {
            val order = book[ids[random.nextInt(ids.size)]] ?: return true
            val (status, outcome) = when (order.status) {
                "CREATED" -> "PAID" to endpoints.payOrder(order.ref, payBody(order.ref, order.total))
                "PAID" -> "SHIPPED" to endpoints.shipOrder(order.ref)
                "SHIPPED" -> "RECEIVED" to endpoints.receiptOrder(order.ref)
                else -> return true
            }
            stats.count(outcome)
            if (outcome.ok) booked(order.copy(status = status, version = outcome.version ?: (order.version + 1)))
            return true
        }

        private fun booked(order: BookedOrder) {
            book[order.ref.id] = order
            touched[order.ref.id] = order
        }
    }

    private fun await(): Int {
        val endpoints = Endpoints.of("v10", required("base"))
        val touched = File(required("touched")).readLines().filter { it.isNotBlank() }.map(BookedOrder::parse)
        val started = System.nanoTime()
        val deadline = started + (options["timeout"]?.toLong() ?: DEFAULT_AWAIT_SECONDS) * NANOS_PER_SECOND
        var waiting = touched
        while (waiting.isNotEmpty() && System.nanoTime() < deadline) {
            waiting = waiting.filter { order -> (endpoints.orderState(order.ref)?.first ?: 0) < order.version }
            if (waiting.isNotEmpty()) Thread.sleep(POLL_MILLIS)
        }
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("""{"command":"await","orders":${touched.size},"behind":${waiting.size},"caughtUpMillis":$millis}""")
        return if (waiting.isEmpty()) OK else FAILED
    }

    private fun states(): Int {
        val endpoints = endpoints()
        val every = options["sample"]?.toInt() ?: 1
        val orders = File(required("book")).readLines().filter { it.isNotBlank() }.map(BookedOrder::parse)
            .filterIndexed { index, _ -> index % every == 0 }
        val states = ConcurrentHashMap<String, JsonNode>()
        parallel(orders.indices) { index ->
            val order = orders[index]
            endpoints.orderState(order.ref)?.let { (_, state) -> states[order.ref.id] = normalized(state) }
        }
        val out = Endpoints.MAPPER.createObjectNode()
        states.toSortedMap().forEach { (id, state) -> out.set(id, state) }
        File(required("out")).writeText(Endpoints.MAPPER.writeValueAsString(out))
        println("""{"command":"states","sampled":${orders.size},"read":${states.size}}""")
        return if (states.size == orders.size) OK else FAILED
    }

    private fun compare(left: File, right: File): Int {
        val a = Endpoints.MAPPER.readTree(left)
        val b = Endpoints.MAPPER.readTree(right)
        val ids = (a.propertyNames() + b.propertyNames()).toSortedSet()
        val differing = ids.filter { id -> a.get(id) == null || b.get(id) == null || !same(a.get(id), b.get(id)) }
        differing.take(MAX_PRINTED).forEach { System.err.println("DIFFERS $it: ${a.get(it)} / ${b.get(it)}") }
        println("""{"command":"compare","instances":${ids.size},"differing":${differing.size}}""")
        return if (differing.isEmpty()) OK else DIFFERS
    }

    /** [state] without what one version derives and the other keeps elsewhere: 9.x's `id`, which V10's ref holds. */
    private fun normalized(state: JsonNode): JsonNode = (state.deepCopy() as ObjectNode).apply { remove("id") }

    private fun same(left: JsonNode, right: JsonNode): Boolean = when {
        left.isNumber && right.isNumber -> left.decimalValue().compareTo(right.decimalValue()) == 0
        left.isObject && right.isObject -> left.propertyNames().toSet() == right.propertyNames().toSet() &&
            left.propertyNames().all { same(left.get(it), right.get(it)) }

        left.isArray && right.isArray -> left.size() == right.size() && (0 until left.size()).all {
            same(left.get(it), right.get(it))
        }
        else -> left == right
    }

    private fun parallel(range: Iterable<Int>, task: (Int) -> Unit) {
        val permits = Semaphore(concurrency)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            range.forEach { index ->
                permits.acquire()
                executor.submit {
                    try {
                        task(index)
                    } catch (failure: Exception) {
                        System.err.println("task $index failed: $failure")
                    } finally {
                        permits.release()
                    }
                }
            }
        }
    }

    private fun createBody(random: Random, quantity: Int): String =
        """{"items":[{"productId":"p${random.nextInt(PRODUCTS)}","price":$PRICE,"quantity":$quantity}],""" +
            """"address":${addressBody(random, field = false)},"fromCart":${random.nextInt(PERCENT) < FROM_CART}}"""

    private fun addressBody(random: Random, field: Boolean = true): String {
        val address = """{"country":"China","province":"P${random.nextInt(PROVINCES)}","city":"C${random.nextInt(PROVINCES)}",""" +
            """"district":"D${random.nextInt(PROVINCES)}","detail":"${random.nextInt(DETAILS)}"}"""
        return if (field) """{"shippingAddress":$address}""" else address
    }

    private fun payBody(ref: OrderRef, total: Int): String = """{"paymentId":"pay-${ref.id}","amount":$total}"""

    /** The commands a run sent, applied and failed, and how fast. */
    private class Stats {
        val sent = AtomicLong()
        val failed = AtomicLong()
        val errors = ConcurrentHashMap<String, AtomicLong>()
        private val started = System.nanoTime()

        fun count(outcome: Outcome) {
            sent.incrementAndGet()
            if (!outcome.ok) {
                failed.incrementAndGet()
                errors.computeIfAbsent(outcome.error.orEmpty().take(ERROR_KEY)) { AtomicLong() }.incrementAndGet()
            }
        }

        fun json(command: String, extra: String): String {
            val seconds = (System.nanoTime() - started).toDouble() / NANOS_PER_SECOND
            val errorList = errors.entries.sortedByDescending { it.value.get() }.take(MAX_PRINTED)
                .joinToString(",") { (error, count) -> "\"${error.replace("\"", "'")}\":${count.get()}" }
            return """{"command":"$command",$extra,"commands":${sent.get()},"failed":${failed.get()},""" +
                """"seconds":${"%.1f".format(seconds)},"perSecond":${"%.0f".format(sent.get() / seconds)},"errors":{$errorList}}"""
        }
    }

    private companion object {
        const val OK = 0
        const val FAILED = 1
        const val USAGE = 2
        const val DIFFERS = 3
        const val DEFAULT_CONCURRENCY = 32
        const val DEFAULT_TENANTS = 4
        const val DEFAULT_AWAIT_SECONDS = 300L
        const val PRICE = 10
        const val PRODUCTS = 20
        const val PROVINCES = 30
        const val DETAILS = 1000
        const val MAX_QUANTITY = 5
        const val PERCENT = 100
        const val ADDRESS_CHANGED = 30
        const val PAID = 70
        const val SHIPPED = 60
        const val RECEIVED = 50
        const val CART_SHARE = 20

        /** The share of orders placed from the customer's cart, which the cart saga then empties of their products. */
        const val FROM_CART = 10
        const val WORKER_SEED = 7919
        const val POLL_MILLIS = 200L
        const val MAX_PRINTED = 20
        const val ERROR_KEY = 120
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

fun main(args: Array<String>) {
    exitProcess(RehearsalDriver(args.toList()).run())
}
