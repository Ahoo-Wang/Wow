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

import com.sun.net.httpserver.HttpServer
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.command.CallHeader
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.runtime.admission.CallContext
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The checkout's synchronous call (roadmap P.12, decision D193): the basket's checkout saga submits the order through
 * the ordering service's command gateway on its internal port, with the basket service's token, for the buyer. The services run in this
 * JVM on the memory backend, as [EshopSharedJvmTest]'s do, so the test controls when the ordering is up.
 *
 * - **Call.** A checkout whose wait follows the call into the ordering context ends once the order is committed
 *   there; the order belongs to its buyer, who is its operator.
 * - **Target down.** While the ordering is down (its address answers 503, as a load balancer in front of a down
 *   service does), the saga is retried within its subscription's budget, then the basket parks. Once the ordering is
 *   back and an operator resumes the basket, the order is submitted, once; the same call again is the same request,
 *   answered with the same order.
 * - **Trust boundary.** Only the basket service, by its token, and only on the ordering's internal port, may assert the
 *   root command a call acts for (`Wow-Call`): the public port refuses it even with that token, and the internal port
 *   refuses a buyer, a caller without a token, and one with a wrong token.
 */
class EshopSyncCallTest {
    @Test
    fun `a checkout should submit the buyer's order through a synchronous call`() {
        ordering().use { ordering ->
            basket(ordering.internalPort).use { basket ->
                basket.addItem("alice")
                val checkedOut = basket.post(
                    "/eshop-basket/basket/alice/checkout-basket",
                    "{}",
                    buyer("alice") + mapOf(WowHeaders.WAIT to CHECKOUT_TO_ORDER, WowHeaders.WAIT_TIMEOUT to "20s"),
                )
                checkedOut.status.assert().describedAs(checkedOut.body).isEqualTo(OK)
                // The wait went on with the call: it ends at the order's commit in the ordering context.
                val result = json.readTree(checkedOut.body)
                result["hop"]["context"].asString().assert().isEqualTo(ORDERING)
                result["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
                result["ref"]["id"].asString().assert().isEqualTo("alice-1")

                ordering.ownerOf("alice-1").assert().isEqualTo("alice")
                val submitted = ordering.submitCommits("alice-1").single()
                submitted["operator"]["id"].asString().assert().isEqualTo("alice")
            }
        }
    }

    @Test
    fun `a checkout with the ordering down should retry, park, and keep its order once the ordering is back`() {
        val down = DownService()
        val port = down.port
        basket(port, retries = MAX_ATTEMPTS).use { basket ->
            basket.addItem("bob")
            val checkedOut = basket.post("/eshop-basket/basket/bob/checkout-basket", "{}", buyer("bob"))
            checkedOut.status.assert().describedAs(checkedOut.body).isEqualTo(OK)

            // Retried within the subscription's budget, then parked.
            val parked = "/wow/ops/parked/$CHECKOUT_SAGA/eshop-basket/basket/${encode(Caller.DEFAULT_TENANT)}/bob"
            eventually("the basket parked") { basket.get(parked).status == OK }
            down.calls.assert().isEqualTo(MAX_ATTEMPTS)
            json.readTree(basket.get(parked).body)["reason"].asString().assert().contains("503")
            down.close()

            ordering(port).use { ordering ->
                val resumed = basket.post("$parked/resume", """{"reason":"The ordering is back."}""", emptyMap())
                resumed.status.assert().describedAs(resumed.body).isEqualTo(OK)
                eventually("bob-1 submitted") { ordering.ownerOf("bob-1") != null }
                ordering.ownerOf("bob-1").assert().isEqualTo("bob")
                eventually("the basket resumed") { basket.get(parked).status == NOT_FOUND }

                // The saga's call again (a replay of the checkout's commit, version 2) is the same request: the
                // ordering answers it with the same order, which it does not submit twice.
                val replay = ordering.call(
                    "/eshop-ordering/order/bob-1/submit-order",
                    ExecutionId(CHECKOUT_SAGA, BASKET.ref(Caller.DEFAULT_TENANT, "bob"), 2),
                    Scope(ownerId = "bob"),
                    TOKEN,
                )
                replay.status.assert().describedAs(replay.body).isEqualTo(OK)
                val again = json.readTree(replay.body)
                again["duplicate"].asBoolean().assert().isTrue()
                again["ref"]["id"].asString().assert().isEqualTo("bob-1")
                ordering.submitCommits("bob-1").assert().hasSize(1)
            }
        }
    }

    @Test
    fun `only the basket service should assert the root command of a call`() {
        ordering().use { ordering ->
            val execution = ExecutionId(CHECKOUT_SAGA, BASKET.ref(Caller.DEFAULT_TENANT, "carol"), 2)
            // The public port refuses a call, even with the basket service's token.
            val public = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                TOKEN,
                internal = false,
            )
            public.status.assert().describedAs(public.body).isEqualTo(FORBIDDEN)
            json.readTree(public.body)["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            // On the internal port, a buyer who sends Wow-Call to act for another buyer is refused.
            val buyer = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                token = null,
                headers = buyer("mallory"),
            )
            buyer.status.assert().describedAs(buyer.body).isEqualTo(FORBIDDEN)
            json.readTree(buyer.body)["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            // So is a caller without a token, and one whose token is not the basket service's.
            val anonymous = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                token = null,
            )
            anonymous.status.assert().describedAs(anonymous.body).isEqualTo(FORBIDDEN)
            val forged = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                token = "forged-token-of-at-least-thirty-two-characters",
            )
            forged.status.assert().describedAs(forged.body).isEqualTo(FORBIDDEN)
            ordering.ownerOf("carol-1").assert().isNull()

            // The basket service's token is trusted: the order is carol's.
            val trusted = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                TOKEN,
            )
            trusted.status.assert().describedAs(trusted.body).isEqualTo(OK)
            ordering.ownerOf("carol-1").assert().isEqualTo("carol")
        }
    }

    /** A service of this JVM, its public and [internalPort] on the loopback interface. */
    private class Service(private val context: ConfigurableApplicationContext, val internalPort: Int) : AutoCloseable {
        val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)
        private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

        fun get(path: String, headers: Map<String, String> = emptyMap()): Answer =
            send(HttpRequest.newBuilder(uri(path)).GET().apply { headers.forEach { (name, value) -> header(name, value) } })

        fun post(path: String, body: String, headers: Map<String, String>): Answer = send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

        fun addItem(buyer: String) {
            val added = post(
                "/eshop-basket/basket/$buyer/add-basket-item",
                """{"productId":"mug","unitPrice":8.50,"quantity":2}""",
                buyer(buyer),
            )
            added.status.assert().describedAs(added.body).isEqualTo(OK)
        }

        /** The owner of the order [id], or null while it does not exist. */
        fun ownerOf(id: String): String? {
            val view = get("/eshop-ordering/order/$id/state", EshopStack.EMPLOYEE)
            if (view.status == NOT_FOUND) return null
            check(view.status == OK) { "The state of $id answered ${view.status}: ${view.body}" }
            return json.readTree(view.body)["scope"]["ownerId"]?.takeUnless { it.isNull }?.asString()
        }

        /** The commits of the order [id] that submitted it. */
        fun submitCommits(id: String): List<JsonNode> {
            val trace = get("/eshop-ordering/order/$id/state/trace", EshopStack.EMPLOYEE)
            check(trace.status == OK) { "The trace of $id answered ${trace.status}: ${trace.body}" }
            return json.readTree(trace.body)["commits"].values().filter { commit ->
                commit["events"].values().any { it["name"].asString().endsWith("OrderSubmitted") }
            }
        }

        /**
         * The call the checkout saga's [execution] makes first, sent to [path] of the internal port (the public one
         * unless [internal]) with [token] and [headers].
         */
        fun call(
            path: String,
            execution: ExecutionId,
            scope: Scope,
            token: String?,
            headers: Map<String, String> = emptyMap(),
            internal: Boolean = true,
        ): Answer {
            val id = ExecutionSagaContext.derivedId(execution, 0)
            val call = CallContext(
                commandId = id,
                tenantId = Caller.DEFAULT_TENANT,
                scope = scope,
                causation = Causation("root-of-$id", "$execution"),
                operator = Operator(checkNotNull(scope.ownerId), Caller.DEFAULT_TENANT),
            )
            val authorization = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
            return send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${if (internal) internalPort else port}$path"))
                    .header("Content-Type", "application/json")
                    .apply {
                        val all = headers + authorization +
                            mapOf(WowHeaders.REQUEST_ID to id, WowHeaders.CALL to CallHeader.format(call))
                        all.forEach { (name, value) -> header(name, value) }
                    }
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
                        ),
                    ),
            )
        }

        private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

        private fun send(request: HttpRequest.Builder): Answer {
            val response = http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
            return Answer(response.statusCode(), response.body())
        }

        override fun close() {
            try {
                context.close()
            } finally {
                http.shutdownNow()
            }
        }
    }

    /** The ordering's address while it is down: 503 to every request, counted. */
    private class DownService : AutoCloseable {
        private val count = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    count.incrementAndGet()
                    exchange.sendResponseHeaders(UNAVAILABLE, -1)
                }
            }
            start()
        }
        val port: Int = server.address.port
        val calls: Int get() = count.get()

        override fun close() {
            server.stop(0)
        }
    }

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val UNAVAILABLE = 503
        const val MAX_ATTEMPTS = 3
        const val ORDERING = "eshop-ordering"
        const val CHECKOUT_SAGA = "BasketCheckoutSaga.onBasketCheckedOut"
        const val CHECKOUT_TO_ORDER = EshopStack.CHECKOUT_TO_ORDER

        /** The basket service's token, at least 32 characters, which the ordering trusts in this test. */
        const val TOKEN = "eshop-sync-call-test-token-0123456789"
        val BASKET = AggregateType("eshop-basket", "basket")
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val DEADLINE: Duration = Duration.ofSeconds(30)

        fun buyer(id: String): Map<String, String> = EshopStack.buyer(id)

        fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8)

        /**
         * The ordering service, trusting the basket service's [TOKEN] on its [internalPort] (a free one by default),
         * where the basket calls it.
         */
        fun ordering(internalPort: Int = freePort()): Service = start(
            OrderingServer::class.java,
            "ordering",
            internalPort,
            "--wow.http.services.eshop-basket-service.tokens[0]=$TOKEN",
            "--eshop.ordering.payment-grace-period=30m",
        )

        /**
         * The basket service, calling the ordering at its internal [orderingPort] with [TOKEN]; its checkout saga makes
         * [retries] attempts 50 ms apart, then parks until an operator resumes it.
         */
        fun basket(orderingPort: Int, retries: Int = 5): Service = start(
            BasketServer::class.java,
            "basket",
            freePort(),
            "--wow.calls.contexts.eshop-ordering.url=http://127.0.0.1:$orderingPort",
            "--wow.calls.token=$TOKEN",
            "--wow.calls.timeout=5s",
            "--wow.ops.enabled=true",
            "--wow.subscriptions.[$CHECKOUT_SAGA].retry.max-attempts=$retries",
            "--wow.subscriptions.[$CHECKOUT_SAGA].retry.initial-backoff=50ms",
            "--wow.subscriptions.[$CHECKOUT_SAGA].retry.max-backoff=50ms",
            "--wow.subscriptions.[$CHECKOUT_SAGA].parked.max-retries=0",
        )

        /**
         * Starts [application] as the eShop service of [context] with its internal port [internalPort], serving
         * `eshop-<context>` alone on the memory backend, its commands to other contexts in memory
         * ([InMemoryTopicsBeans]), with the further [arguments]: the services share one class path, so Spring Boot reads
         * one `application.yaml` for both.
         */
        fun start(application: Class<*>, context: String, internalPort: Int, vararg arguments: String) = Service(
            SpringApplicationBuilder(application, InMemoryTopicsBeans::class.java).run(
                *arguments,
                "--spring.main.banner-mode=off",
                "--spring.application.name=eshop-$context-service",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--wow.service=eshop-$context-service",
                "--wow.backend=memory",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=$internalPort",
                "--wow.contexts.include=eshop-$context",
            ),
            internalPort,
        )

        fun freePort(): Int = EshopService.freePort()

        fun eventually(what: String, condition: () -> Boolean) {
            val until = System.nanoTime() + DEADLINE.toNanos()
            while (!condition()) {
                check(System.nanoTime() < until) { "Not reached in $DEADLINE: $what." }
                Thread.sleep(POLL)
            }
        }

        const val POLL = 50L
    }
}
