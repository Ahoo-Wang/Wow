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
import java.net.URI
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
 * The load driver of the blue-green rehearsal (.1), against the 9.x example-server or the rehearsal host:
 *
 * ```text
 * driver seed    --version=9x|v10 --base=<url> --book=<file> --orders=N [--carts=M] [--tenants=T] [--concurrency=C]
 * driver traffic --version=9x|v10 --base=<url> --book=<file> --seconds=S [--touched=<file>] [--create=K] [--concurrency=C]
 * driver await   --base=<v10 url> --touched=<file> [--timeout=S]
 * driver states  --version=9x|v10 --base=<url> --book=<file> --out=<file> [--sample=N]
 * driver access  --base=<v10 url> --book=<file> [--tenant=t0] [--sample=N]
 * driver compare <states a> <states b>
 * driver views …  (the view store's part, see ViewsDriver)
 * ```
 *
 * - `seed` creates N orders, each taken through a lifecycle drawn from its index (some only created, some with a
 *   changed address, some paid, shipped and received), and M carts; the order book records where each order stands.
 *   Half the orders are tagged, before any address change, with a region and teams (9.x's tags command); some are
 *   tagged again with another region and no team, some cleared.
 * - `traffic` moves existing orders on by one step at a time for S seconds, and adds to carts; with `--create`, it
 *   creates K new orders instead, and nothing else. Some of its steps tag an order again. It records each order it
 *   wrote and its version in the touched file.
 * - `await` waits until the rehearsal host holds every touched order at its version: the tail's lag behind 9.x.
 * - `states` reads every N-th order's state; `compare` compares two such files (of orders, or of views), numbers by
 *   value, and exits with 3 when one differs. It leaves out, on both sides, the instances either file lists as stale
 *   (`views states` on 9.x), and counts them.
 * - `access` reads a tenant's orders under the access policy, once the access walk has stamped them: a caller of the
 *   region most of them are tagged with reads every N-th order's state, and its streams, when it is of that region,
 *   and neither otherwise; and no stream of another order is among the events it finds.
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
        "access" -> access()
        "compare" -> compare(File(args[1]), File(args[2]))
        "views" -> ViewsDriver(args.drop(1), concurrency).run()
        else -> {
            System.err.println("Usage: driver seed|traffic|await|states|access|compare|views … (see RehearsalDriver)")
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
            book[ref.id] = lifecycle(endpoints, ref, random, Random(index + TAG_SEED), stats)
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
        println(
            stats.json("seed", "\"orders\":$orders,\"carts\":$carts,\"tenants\":$tenants,\"tags\":${stats.tags.get()}")
        )
        return if (stats.failed.get() == 0L) OK else FAILED
    }

    /** Creates [ref] and takes it as far through its lifecycle as [random] draws, tagging it as [tagging] draws. */
    private fun lifecycle(
        endpoints: Endpoints,
        ref: OrderRef,
        random: Random,
        tagging: Random,
        stats: Stats
    ): BookedOrder {
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
        // Before the address changes: 9.x's tags extractor then keeps the former province among the order's tags.
        for ((region, teams) in tagsOf(tagging)) {
            if (!step("CREATED", endpoints.tagOrder(ref, region, teams))) return booked
            stats.tags.incrementAndGet()
        }
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

        /** Moves a random order on by one step, or tags it again; one already received is only tagged. */
        private fun advance(random: Random): Boolean {
            val order = book[ids[random.nextInt(ids.size)]] ?: return true
            if (random.nextInt(PERCENT) < TAG_SHARE) {
                val outcome = endpoints.tagOrder(order.ref, REGIONS[random.nextInt(REGIONS.size)], teamsOf(random))
                stats.count(outcome)
                if (outcome.ok) booked(order.copy(version = outcome.version ?: (order.version + 1)))
                return true
            }
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

    private fun access(): Int {
        val endpoints = Endpoints.V10(URI.create(required("base")))
        val tenant = options["tenant"] ?: "t0"
        val every = options["sample"]?.toInt() ?: 1
        val orders = File(required("book")).readLines().filter { it.isNotBlank() }.map(BookedOrder::parse)
            .filter { it.ref.tenant == tenant }
        // Each order's version and region, read by a caller of the whole tenant.
        val read = ConcurrentHashMap<String, Pair<Int, String?>>()
        parallel(orders.indices) { index ->
            val ref = orders[index].ref
            endpoints.accessState(ref)?.let { (version, state) ->
                read[ref.id] = version to state.get("region")?.takeUnless { it.isNull }?.asString()
            }
        }
        val region = read.values.mapNotNull { it.second }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (region == null) {
            println(
                """{"command":"access","tenant":"$tenant","orders":${orders.size},"read":${read.size},"tagged":0}"""
            )
            return FAILED
        }
        val sampled = orders.filterIndexed { index, _ -> index % every == 0 }.filter { read.containsKey(it.ref.id) }
        val visible = AtomicLong()
        val streams = AtomicLong()
        val wrongStates = AtomicLong()
        val wrongStreams = AtomicLong()
        parallel(sampled.indices) { index ->
            val ref = sampled[index].ref
            val (version, tagged) = read.getValue(ref.id)
            val sees = tagged == region
            if (sees) {
                visible.incrementAndGet()
                streams.addAndGet(version.toLong())
            }
            if (endpoints.stateStatus(ref, region) != if (sees) Endpoints.OK else Endpoints.NOT_FOUND) {
                wrongStates.incrementAndGet()
            }
            if (endpoints.streams(ref, region) != if (sees) version.toLong() else 0L) wrongStreams.incrementAndGet()
        }
        val listed = endpoints.streamOrders(tenant, region, LIST_LIMIT)
        val leaked = listed.count { id -> read[id]?.second != region }
        println(
            """{"command":"access","tenant":"$tenant","region":"$region","orders":${orders.size},"read":${read.size},""" +
                """"sampled":${sampled.size},"visible":${visible.get()},"streams":${streams.get()},""" +
                """"wrongStates":${wrongStates.get()},"wrongStreams":${wrongStreams.get()},""" +
                """"listed":${listed.size},"leaked":$leaked}""",
        )
        val wrong = wrongStates.get() + wrongStreams.get() + leaked
        return if (read.size == orders.size && listed.isNotEmpty() && wrong == 0L) OK else FAILED
    }

    private fun compare(left: File, right: File): Int {
        val a = Endpoints.MAPPER.readTree(left)
        val b = Endpoints.MAPPER.readTree(right)
        val stale = listOf(a, b).flatMap { it.get(Endpoints.STALE)?.propertyNames().orEmpty() }.toSet()
        val ids = (a.propertyNames() + b.propertyNames()).filter { it != Endpoints.STALE && it !in stale }.toSortedSet()
        val differing = ids.filter { id -> a.get(id) == null || b.get(id) == null || !same(a.get(id), b.get(id)) }
        differing.take(MAX_PRINTED).forEach { System.err.println("DIFFERS $it: ${a.get(it)} / ${b.get(it)}") }
        println("""{"command":"compare","instances":${ids.size},"differing":${differing.size},"stale":${stale.size}}""")
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

    /**
     * The tags of an order, in the order they are applied, each a region (none for null) and teams: none for half the
     * orders; else a region and teams, then for some another region and no team (a key the replace removes), or for
     * some nothing (a clear, 9.x's empty map).
     */
    private fun tagsOf(random: Random): List<Pair<String?, List<String>>> {
        if (random.nextInt(PERCENT) >= TAGGED) {
            return emptyList()
        }
        val first = REGIONS[random.nextInt(REGIONS.size)]
        val tags = mutableListOf<Pair<String?, List<String>>>(first to teamsOf(random))
        val again = random.nextInt(PERCENT)
        when {
            again < RETAGGED -> tags += REGIONS.filter { it != first }[random.nextInt(REGIONS.size - 1)] to emptyList()
            again < RETAGGED + CLEARED -> tags += null to emptyList()
        }
        return tags
    }

    /** One team or more. */
    private fun teamsOf(random: Random): List<String> =
        TEAMS.filter { random.nextBoolean() }.ifEmpty { listOf(TEAMS[random.nextInt(TEAMS.size)]) }

    /** The commands a run sent, applied and failed, and how fast. */
    private class Stats {
        val sent = AtomicLong()
        val failed = AtomicLong()

        /** The tags commands applied: one 9.x stream each. */
        val tags = AtomicLong()
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
        const val TAGGED = 50
        const val RETAGGED = 30
        const val CLEARED = 10

        /** The share of traffic steps that tag an order again. */
        const val TAG_SHARE = 10
        const val TAG_SEED = 104_729
        const val LIST_LIMIT = 1000
        val REGIONS = listOf("east", "west", "north", "south")
        val TEAMS = listOf("a", "b", "c")

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
