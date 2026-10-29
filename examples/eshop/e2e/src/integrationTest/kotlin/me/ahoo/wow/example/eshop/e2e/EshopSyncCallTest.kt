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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.example.eshop.basket.api.basket.EshopBasketContract
import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.WowServer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContextException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The checkout's synchronous call (roadmap P.12, decision D193): the basket's checkout saga submits the order through
 * the ordering service's command gateway on its internal port, naming the basket service (`Wow-Service`, decision
 * D300), for the buyer. The services run in this JVM on the memory backend, as [EshopSharedJvmTest]'s do, so the test controls when the ordering is up.
 *
 * - **Call.** A checkout whose wait follows the call into the ordering context ends once the order is committed
 *   there; the order belongs to its buyer, who is its operator.
 * - **Target down.** While the ordering is down (its address answers 503, as a load balancer in front of a down
 *   service does), the saga is retried within its subscription's budget, then the basket parks. Once the ordering is
 *   back and an operator resumes the basket, the order is submitted, once; the same call again is the same request,
 *   answered with the same order.
 * - **Trust boundary.** Only a service that names itself, and only on the ordering's internal port, may assert the
 *   root command a call acts for (`Wow-Call`): the public port refuses it even naming the basket service, and the
 *   internal port refuses a buyer and a caller that names no service.
 */
class EshopSyncCallTest {
    @Test
    fun `a checkout should submit the buyer's order through a synchronous call`() {
        ordering().use { ordering ->
            basket(ordering.internalPort).use { basket ->
                basket.addItem("alice")
                val checkedOut = basket.post(
                    "/eshop-basket/basket/checkout-basket",
                    "{}",
                    buyer("alice") + mapOf(WowHeaders.WAIT to CHECKOUT_TO_ORDER, WowHeaders.WAIT_TIMEOUT to "20s"),
                )
                checkedOut.status.assert().describedAs(checkedOut.text).isEqualTo(OK)
                // The wait went on with the call: it ends at the order's commit in the ordering context.
                val result = checkedOut.body!!
                result["hop"]["context"].asString().assert().isEqualTo(ORDERING)
                result["hop"]["stage"].asString().assert().isEqualTo("committed")
                result["ref"]["id"].asString().assert().isEqualTo("alice-1")

                // The state read is the snapshot's, written after the COMMITTED answer.
                eventually("alice-1 submitted") { ordering.ownerOf("alice-1") != null }
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
            val checkedOut = basket.post("/eshop-basket/basket/checkout-basket", "{}", buyer("bob"))
            checkedOut.status.assert().describedAs(checkedOut.text).isEqualTo(OK)

            // Retried within the subscription's budget, then parked.
            val parked = "/wow/ops/parked/$CHECKOUT_SAGA/eshop-basket/basket/${encode(Caller.DEFAULT_TENANT)}/bob"
            eventually("the basket parked") { basket.get(parked).status == OK }
            down.calls.assert().isEqualTo(MAX_ATTEMPTS)
            basket.get(parked).body!!["reason"].asString().assert().contains("503")
            down.close()

            ordering(port).use { ordering ->
                val resumed = basket.post("$parked/resume", """{"reason":"The ordering is back."}""", emptyMap())
                resumed.status.assert().describedAs(resumed.text).isEqualTo(OK)
                eventually("bob-1 submitted") { ordering.ownerOf("bob-1") != null }
                ordering.ownerOf("bob-1").assert().isEqualTo("bob")
                eventually("the basket resumed") { basket.get(parked).status == NOT_FOUND }

                // The saga's call again (a replay of the checkout's commit, version 2) is the same request: the
                // ordering answers it with the same order, which it does not submit twice.
                val replay = ordering.call(
                    "/eshop-ordering/order/bob-1/submit-order",
                    ExecutionId(CHECKOUT_SAGA, BASKET.ref(Caller.DEFAULT_TENANT, "bob"), 2),
                    Scope(ownerId = "bob"),
                    SERVICE,
                )
                replay.status.assert().describedAs(replay.text).isEqualTo(OK)
                val again = replay.body!!
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
            // The public port refuses a call, even naming the basket service.
            val public = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                SERVICE,
                internal = false,
            )
            public.status.assert().describedAs(public.text).isEqualTo(FORBIDDEN)
            public.body!!["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            // On the internal port, a buyer who sends Wow-Call to act for another buyer is refused.
            val buyer = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                service = null,
                headers = buyer("mallory"),
            )
            buyer.status.assert().describedAs(buyer.text).isEqualTo(FORBIDDEN)
            buyer.body!!["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            // So is a caller that names no service, and one whose name is no service name.
            val anonymous = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                service = null,
            )
            anonymous.status.assert().describedAs(anonymous.text).isEqualTo(FORBIDDEN)
            val forged = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                service = "basket service",
            )
            forged.status.assert().describedAs(forged.text).isEqualTo(FORBIDDEN)
            ordering.ownerOf("carol-1").assert().isNull()

            // The basket service names itself: the order is carol's.
            val trusted = ordering.call(
                "/eshop-ordering/order/carol-1/submit-order",
                execution,
                Scope(ownerId = "carol"),
                SERVICE,
            )
            trusted.status.assert().describedAs(trusted.text).isEqualTo(OK)
            // A call is answered at COMMITTED (D193); the state read is the snapshot's, written right after.
            eventually("carol-1 submitted") { ordering.ownerOf("carol-1") != null }
            ordering.ownerOf("carol-1").assert().isEqualTo("carol")
        }
    }

    /** A service of this JVM, its ports on the loopback interface, as bound. */
    private class Service(private val server: WowServer) : AutoCloseable {
        val internalPort: Int get() = server.internalPort

        fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = server.http.get(path, headers)

        fun post(path: String, body: String, headers: Map<String, String>): Answer =
            server.http.post(path, body, headers)

        fun addItem(buyer: String) {
            val added = post(
                "/eshop-basket/basket/add-basket-item",
                """{"productId":"mug","unitPrice":8.50,"quantity":2}""",
                buyer(buyer),
            )
            added.status.assert().describedAs(added.text).isEqualTo(OK)
        }

        /** The owner of the order [id], or null while it does not exist. */
        fun ownerOf(id: String): String? {
            val view = get("/eshop-ordering/order/$id/state", EshopStack.EMPLOYEE)
            if (view.status == NOT_FOUND) return null
            check(view.status == OK) { "The state of $id answered $view" }
            return view.body!!["scope"]["ownerId"]?.takeUnless { it.isNull }?.asString()
        }

        /** The commits of the order [id] that submitted it. */
        fun submitCommits(id: String): List<JsonNode> {
            val trace = get("/eshop-ordering/order/$id/state/trace", EshopStack.EMPLOYEE)
            check(trace.status == OK) { "The trace of $id answered $trace" }
            return trace.body!!["commits"].values().filter { commit ->
                commit["events"].values().any { it["name"].asString().endsWith("OrderSubmitted") }
            }
        }

        /**
         * The call the checkout saga's [execution] makes, the submission of the order [path] names, sent to [path] of
         * the internal port (the public one unless [internal]) naming [service] and with [headers].
         */
        fun call(
            path: String,
            execution: ExecutionId,
            scope: Scope,
            service: String?,
            headers: Map<String, String> = emptyMap(),
            internal: Boolean = true,
        ): Answer {
            // `/eshop-ordering/order/{id}/submit-order`
            val order = EshopOrderingContract.Order.TYPE.ref(Caller.DEFAULT_TENANT, path.split('/')[3])
            val id = ExecutionSagaContext.derivedId(execution, order, SubmitOrder::class.java)
            val call = CallContext.of(
                commandId = id,
                tenantId = Caller.DEFAULT_TENANT,
                scope = scope,
                causation = Causation("root-of-$id", "$execution"),
                operator = Operator.of(checkNotNull(scope.ownerId), Caller.DEFAULT_TENANT),
            )
            val authorization = service?.let { mapOf(WowHeaders.SERVICE to it) }.orEmpty()
            return (if (internal) server.internal else server.http).post(
                path,
                """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
                headers + authorization + mapOf(WowHeaders.REQUEST_ID to id, WowHeaders.CALL to CallHeaders.format(call)),
            )
        }

        override fun close() {
            server.close()
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

        /** The basket service's name, which it states on the ordering's internal port. */
        const val SERVICE: String = "eshop-basket-service"
        val BASKET = EshopBasketContract.Basket.TYPE
        val DEADLINE: Duration = Duration.ofSeconds(30)

        fun buyer(id: String): Map<String, String> = EshopStack.buyer(id)

        fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8)

        /**
         * The ordering service, taking the basket service's calls on its [internalPort] (one the system chooses by
         * default), where the basket calls it.
         */
        fun ordering(internalPort: Int = 0): Service = start(
            OrderingServer::class.java,
            "ordering",
            internalPort,
            mapOf("eshop.ordering.payment-grace-period" to "30m"),
        )

        /**
         * The basket service, calling the ordering at its internal [orderingPort] as itself; its checkout saga makes
         * [retries] attempts 50 ms apart, then parks until an operator resumes it.
         */
        fun basket(orderingPort: Int, retries: Int = 5): Service = start(
            BasketServer::class.java,
            "basket",
            0,
            mapOf(
                "wow.peers.eshop-ordering.url" to "http://127.0.0.1:$orderingPort",
                "wow.peer.timeout" to "5s",
                "wow.http.ops.enabled" to true,
                "wow.subscriptions.[$CHECKOUT_SAGA].retry.max-attempts" to retries,
                "wow.subscriptions.[$CHECKOUT_SAGA].retry.initial-backoff" to "50ms",
                "wow.subscriptions.[$CHECKOUT_SAGA].retry.max-backoff" to "50ms",
                "wow.subscriptions.[$CHECKOUT_SAGA].parked.max-retries" to 0,
            ),
            // The operator's actions are refused without a CommandAuthorizer (review A3-3): the test's operator may act.
            OperatorActions::class.java,
        )

        /**
         * Starts [application] as the eShop service of [context] with its internal port [internalPort] (0: one the
         * system chooses), serving `eshop-<context>` alone on the memory backend, its commands to other contexts in
         * memory ([InMemoryTopicsBeans]), with the further [properties] and configuration [sources]: the services share
         * one class path, so Spring Boot reads one `application.yaml` for both, and each gets what it needs as
         * command-line arguments. A service given the port another one released is started again,
         * at most [BIND_ATTEMPTS] times, while another process holds it.
         */
        fun start(
            application: Class<*>,
            context: String,
            internalPort: Int,
            properties: Map<String, Any?>,
            vararg sources: Class<*>,
        ): Service {
            var attempt = 1
            while (true) {
                try {
                    return startOnce(application, context, internalPort, properties, *sources)
                } catch (failure: ApplicationContextException) {
                    val taken = generateSequence<Throwable>(failure) { it.cause }
                        .any { it.message.orEmpty().contains("already in use", ignoreCase = true) }
                    if (internalPort == 0 || !taken || attempt == BIND_ATTEMPTS) throw failure
                    attempt++
                }
            }
        }

        private fun startOnce(
            application: Class<*>,
            context: String,
            internalPort: Int,
            properties: Map<String, Any?>,
            vararg sources: Class<*>,
        ) = Service(
            wowServer(
                application,
                listOf("dev"),
                properties + mapOf(
                    "spring.application.name" to "eshop-$context-service",
                    "wow.internal.port" to internalPort,
                    "wow.contexts.include" to "eshop-$context",
                ),
                listOf(InMemoryTopicsBeans::class.java, *sources),
            ),
        )

        const val BIND_ATTEMPTS = 5

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

/** Lets every caller act on the basket's parked entries, the test's operator among them (review A3-3). */
@Configuration(proxyBeanMethods = false)
class OperatorActions {
    @Bean
    fun basketOperatorAuthorizer(): CommandAuthorizer = CommandAuthorizer.ALLOW_ALL
}
