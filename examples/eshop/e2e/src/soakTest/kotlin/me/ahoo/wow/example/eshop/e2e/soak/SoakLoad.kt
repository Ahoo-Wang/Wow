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

import me.ahoo.wow.example.eshop.e2e.EshopStack
import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * What an order of the soak is meant to do, chosen by its number: most are paid and shipped, one in ten asks for more
 * of a product than its stock holds (the inventory rejects the reservation and the order is cancelled), one in ten
 * costs more than the simulated gateway approves (the payment fails and the order is cancelled, its stock released).
 */
enum class Scenario(val product: String, val unitPrice: String, val wait: String) {
    PAID(SoakLoad.PRODUCT_A, "8.50", EshopStack.CHECKOUT_TO_PAID),
    SHORT_STOCK(SoakLoad.PRODUCT_SHORT, "5.00", EshopStack.CHECKOUT_TO_PAID),

    /** The wait ends where the gateway's answer is committed in the payment: a failed payment takes another saga. */
    DECLINED(SoakLoad.PRODUCT_B, "1200.00", EshopStack.CHECKOUT_TO_ORDER + DECLINED_TAIL),
    ;

    companion object {
        fun of(number: Int): Scenario = when (number % 10) {
            3 -> DECLINED
            7 -> SHORT_STOCK
            else -> PAID
        }
    }
}

private const val DECLINED_TAIL =
    " > handled(eshop-ordering/OrderProcessSaga.onOrderSubmitted) > committed(eshop-ordering)" +
        " > handled(eshop-ordering/OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)" +
        " > handled(eshop-ordering/OrderProcessSaga.onReserveCommitted) > committed(eshop-ordering)" +
        " > handled(eshop-payment/OrderPaymentSaga.onOrderStockConfirmed) > committed(eshop-payment)" +
        " > handled(eshop-payment/PaymentGatewaySaga.onPaymentRequested) > committed(eshop-payment)"

/**
 * One logical order: the buyer `c<client>-<number>` and its one checkout. The ordering numbers the order
 * ([orderId]): the checkout's answer names it when it reached the order's commit, and the checks find it by its buyer,
 * the order's owner, otherwise.
 */
class SoakOrder(val client: Int, val number: Int) {
    val buyer = "c$client-$number"

    /** The number the ordering gave the order, once known; null before, and for a checkout that placed none. */
    @Volatile
    var orderId: String? = null
    val scenario = Scenario.of(number)
    val quantity = if (scenario == Scenario.SHORT_STOCK) 2 else 1 + number % 3

    /** The requests sent for it, in order. */
    val requests = mutableListOf<SoakRequest>()
}

/** A request with its deterministic id ([requestId]), and every attempt to send it. */
class SoakRequest(val requestId: String, val kind: String, val context: String) {
    val attempts = mutableListOf<Attempt>()
    val final: Attempt? get() = attempts.lastOrNull()

    /** Whether an attempt was answered that the command is committed (a first commit or a duplicate). */
    val committed: Boolean get() = attempts.any { it.outcome == Outcome.SUCCEEDED || it.outcome == Outcome.DUPLICATE }
}

enum class Outcome(val retried: Boolean) {
    SUCCEEDED(false),

    /** A retry of a request already committed: the kernel deduplicated it (decision D63). */
    DUPLICATE(false),
    REJECTED(false),

    /** `wow.wait.timeout`: the wait ended at its last hop reached. */
    TIMEOUT(true),

    /** Another framework failure (`wow.*`, a 5xx). */
    FAILED(true),

    /** No answer: the connection failed or was cut, the node killed, say. */
    TRANSPORT(true),

    /** No answer within the wait's timeout and the grace after it: a wait left hanging. */
    HUNG(true),
}

/**
 * One attempt: when it was sent ([at], epoch milliseconds), how long it took, its [outcome], the HTTP [status], the
 * error [code], the [hop] its result reached (`stage(context/subscription)`) and the [function] that answered.
 */
data class Attempt(
    val at: Long,
    val millis: Long,
    val outcome: Outcome,
    val status: Int? = null,
    val code: String? = null,
    val hop: String? = null,
    val function: String? = null,
    val node: String? = null,
    val detail: String? = null,
    /** The order the result names (the order or its payment): its number, which the ordering gave it. */
    val order: String? = null,
)

/**
 * The load: [SoakConfig.clients] clients, each sending one order at a time, together [SoakConfig.rate] orders a
 * second. An order adds an item to a new basket (`<buyer>:add`), checks it out with a `Wow-Wait` that follows the
 * order as far as its [Scenario] goes (`<buyer>:checkout`), and ships it once paid (`<order>:ship`). Every request
 * carries its id as `Wow-Request-Id` and is retried with the same id after a failure that may not have reached the
 * log: a transport error, a timeout, a framework failure.
 */
class SoakLoad(private val stack: SoakStack, private val config: SoakConfig, private val log: (String) -> Unit) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val running = AtomicBoolean(true)
    private val threads = mutableListOf<Thread>()

    /** Every order started, in the order they started. */
    val orders = ConcurrentLinkedQueue<SoakOrder>()

    fun start() {
        val period = Duration.ofMillis((config.clients * MILLIS_PER_SECOND / config.rate).toLong())
        repeat(config.clients) { client ->
            threads += thread(name = "soak-client-$client", isDaemon = true) {
                var number = 0
                var next = System.nanoTime() + period.toNanos() * client / config.clients
                while (running.get()) {
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / NANOS_PER_MILLI)
                    if (!running.get()) break
                    next = maxOf(next + period.toNanos(), System.nanoTime())
                    val order = SoakOrder(client, ++number)
                    orders += order
                    runCatching { run(order) }.onFailure { log("client $client: order ${order.buyer} threw $it") }
                }
            }
        }
    }

    /** Stops starting orders, and waits for the ones in flight. */
    fun stop() {
        running.set(false)
        threads.forEach { it.join(Duration.ofMinutes(2).toMillis()) }
    }

    private fun run(order: SoakOrder) {
        val buyer = EshopStack.buyer(order.buyer)
        val add = send(
            order,
            "${order.buyer}:add",
            "add",
            "basket",
            "/eshop-basket/basket/add-basket-item",
            """{"productId":"${order.scenario.product}","unitPrice":${order.scenario.unitPrice},"quantity":${order.quantity}}""",
            buyer + (WAIT to "committed"),
        )
        if (!add.committed) return
        val checkout = send(
            order,
            "${order.buyer}:checkout",
            "checkout",
            "basket",
            "/eshop-basket/basket/checkout-basket",
            "{}",
            buyer + mapOf(WAIT to order.scenario.wait, WAIT_TIMEOUT to "${config.waitTimeout.toSeconds()}s"),
        )
        order.orderId = checkout.attempts.firstNotNullOfOrNull { it.order }
        val paid = checkout.attempts.any { it.outcome == Outcome.SUCCEEDED && it.function == "PayOrder" }
        if (order.scenario == Scenario.PAID && paid) ship(order)
    }

    /** Ships [order], once paid and numbered; the quiescence ships those whose checkout did not see the payment. */
    fun ship(order: SoakOrder): SoakRequest {
        val id = checkNotNull(order.orderId) { "The order of ${order.buyer} has no number yet." }
        return send(
            order,
            "$id:ship",
            "ship",
            "ordering",
            "/eshop-ordering/order/$id/ship-order",
            "{}",
            mapOf(WAIT to "committed"),
        )
    }

    @Suppress("LongParameterList")
    private fun send(
        order: SoakOrder,
        requestId: String,
        kind: String,
        context: String,
        path: String,
        body: String,
        headers: Map<String, String>,
    ): SoakRequest {
        val request = SoakRequest(requestId, kind, context)
        synchronized(order) { order.requests += request }
        var backoff = FIRST_BACKOFF
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Thread.sleep(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
            }
            val result = attempt(context, path, body, headers + (REQUEST_ID to requestId))
            synchronized(request) { request.attempts += result }
            if (!result.outcome.retried) return request
        }
        return request
    }

    private fun attempt(context: String, path: String, body: String, headers: Map<String, String>): Attempt {
        val at = System.currentTimeMillis()
        val begun = System.nanoTime()
        val nodes = stack.nodes(context)
        if (nodes.isEmpty()) return Attempt(at, 0, Outcome.TRANSPORT, detail = "no $context node")
        val node = nodes[(begun % nodes.size).toInt().let { if (it < 0) -it else it }]
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${node.service.publicPort}$path"))
            .header("Content-Type", "application/json")
            .timeout(config.waitTimeout + HUNG_GRACE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return try {
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            classify(at, millisSince(begun), node.id, response.statusCode(), response.body())
        } catch (timeout: HttpTimeoutException) {
            Attempt(at, millisSince(begun), Outcome.HUNG, node = node.id, detail = timeout.toString())
        } catch (failure: IOException) {
            Attempt(at, millisSince(begun), Outcome.TRANSPORT, node = node.id, detail = failure.toString())
        }
    }

    private fun millisSince(begun: Long): Long = (System.nanoTime() - begun) / NANOS_PER_MILLI

    companion object {
        const val PRODUCT_A = "soak-a"
        const val PRODUCT_B = "soak-b"

        /** A product of one unit, which every order of it asks two of. */
        const val PRODUCT_SHORT = "soak-short"
        const val WAIT = "Wow-Wait"
        const val WAIT_TIMEOUT = "Wow-Wait-Timeout"
        const val REQUEST_ID = "Wow-Request-Id"
        const val MAX_ATTEMPTS = 10
        private const val MILLIS_PER_SECOND = 1000.0
        private const val NANOS_PER_MILLI = 1_000_000L
        private val FIRST_BACKOFF: Duration = Duration.ofSeconds(1)
        private val MAX_BACKOFF: Duration = Duration.ofSeconds(5)

        /** The aggregates a result names an order by: the order, or its payment, whose id is its order's number. */
        private val NUMBERED = setOf("eshop-ordering" to "order", "eshop-payment" to "payment")

        /** How long after its wait's timeout an answer may still come before the wait counts as hanging. */
        val HUNG_GRACE: Duration = Duration.ofSeconds(15)

        /** The outcome of an answer: a [CommandResult][me.ahoo.wow.api.result.CommandResult], or a problem holding one. */
        fun classify(at: Long, millis: Long, node: String, status: Int, body: String): Attempt {
            val tree: JsonNode? = runCatching { json.readTree(body) }.getOrNull()
            if (tree == null || !tree.isObject) {
                val outcome = if (status in OK_RANGE) Outcome.SUCCEEDED else Outcome.FAILED
                return Attempt(at, millis, outcome, status, node = node, detail = body.take(DETAIL))
            }
            val result = if (status in OK_RANGE) tree else tree["result"]
            val hop = result?.get("hop")?.takeUnless { it.isNull }?.let(::hop)
            val function = result?.get("function")?.get("name")?.asString()
            val order = result?.get("ref")?.takeUnless { it.isNull }?.let(::orderOf)
            if (status in OK_RANGE) {
                val duplicate = tree["duplicate"]?.asBoolean() == true
                return Attempt(
                    at,
                    millis,
                    if (duplicate) Outcome.DUPLICATE else Outcome.SUCCEEDED,
                    status,
                    null,
                    hop,
                    function,
                    node,
                    order = order,
                )
            }
            val code = tree["code"]?.asString()
            val outcome = when {
                code == "wow.wait.timeout" -> Outcome.TIMEOUT
                result?.get("status")?.asString() == "rejected" -> Outcome.REJECTED
                code != null && !code.startsWith("wow.") && status < SERVER_ERROR -> Outcome.REJECTED
                else -> Outcome.FAILED
            }
            return Attempt(
                at, millis, outcome, status, code, hop, function, node,
                tree["detail"]?.asString()?.take(
                    DETAIL
                )
            )
        }

        /**
         * The order a result's aggregate [ref] names: the order itself, or its payment, whose id is its order's number;
         * null for any other aggregate (the basket's commit).
         */
        private fun orderOf(ref: JsonNode): String? {
            val type = ref["type"] ?: return null
            val named = type["context"]?.asString() to type["name"]?.asString()
            return ref["id"]?.asString()?.takeIf { named in NUMBERED }
        }

        /** A hop as the `Wow-Wait` header spells it: `stage(context/subscription)`. */
        private fun hop(node: JsonNode): String {
            val stage = node["stage"]?.asString()?.lowercase() ?: "?"
            val context = node["context"]?.takeUnless { it.isNull }?.asString()
            val subscription = node["subscription"]?.takeUnless { it.isNull }?.asString()
            val argument = listOfNotNull(context, subscription).joinToString("/")
            return if (argument.isEmpty()) stage else "$stage($argument)"
        }

        private val OK_RANGE = 200..299
        private const val SERVER_ERROR = 500
        private const val DETAIL = 300
    }
}
