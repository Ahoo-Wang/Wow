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

package me.ahoo.wow.spring.boot

import com.sun.net.httpserver.HttpServer
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.http.ops.WebhookNotifier
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The long-horizon retries of parked entries, the operator's routes and the failure webhook on one host (roadmap
 * P.1), end to end over HTTP: `flaky` fails twice and then succeeds, `broken` always fails and may retry once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostParkedOpsTest {
    private val hooks = CopyOnWriteArrayList<JsonNode>()
    private val webhook = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            hooks.add(mapper.readTree(exchange.requestBody.readAllBytes()))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }

    private val host = startHost(
        "wow.subscription.retry.max-attempts" to 1,
        "wow.subscription.parked.max-retries" to 5,
        "wow.subscription.parked.min-backoff" to "200ms",
        "wow.subscription.parked.execution-timeout" to "100ms",
        "wow.subscription.parked.scan-interval" to "50ms",
        "wow.subscriptions.[$BROKEN].parked.max-retries" to 1,
        "wow.ops.enabled" to true,
        "wow.ops.webhook.url" to "http://127.0.0.1:${webhook.address.port}/hook",
        "wow.ops.webhook.format" to "generic",
        "wow.ops.webhook.events" to "parked,exhausted",
        "wow.ops.webhook.headers.[X-Token]" to "t",
        sources = listOf(HostTestApp::class.java, FailingSubscriptions::class.java),
    )
    private val failing: FailingSubscriptions get() = host.context.getBean(FailingSubscriptions::class.java)

    @AfterAll
    fun close() {
        host.close()
        webhook.stop(0)
    }

    private fun openAccount(): String =
        host.post("/bank/account/open-account", """{"owner":"alice"}""").body!!["ref"]["id"].asString()

    private fun entry(subscription: String, id: String) = "${OpsPaths.PARKED}/$subscription/$BANK/account/" +
        "${Caller.DEFAULT_TENANT}/$id"

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(20)
        }
    }

    @Test
    fun `the properties should configure the retries, the routes and the webhook`() {
        val parked = host.node.options.subscription.parked
        parked.spec.assert().isEqualTo(ParkedRetrySpec(5, 200.milliseconds, 100.milliseconds))
        parked.scanInterval.assert().isEqualTo(50.milliseconds)
        parked.specOf(BROKEN).assert().isEqualTo(ParkedRetrySpec(1, 200.milliseconds, 100.milliseconds))
        parked.specOf(FLAKY).assert().isEqualTo(parked.spec)
        host.context.getBean(WebhookNotifier::class.java).toString().assert().contains("GENERIC")
        WowProperties.ParkedSpec().specOver(ParkedRetrySpec()).assert().isNull()
        WowProperties.Parked().spec().assert().isEqualTo(ParkedRetrySpec(10, 3.minutes, 2.minutes))
        // The ledger is published to ops only when asked (decision D141).
        host.node.options.ledger.assert().isNull()
        host.node.ledgerPublisher.assert().isNull()
        val publish = WowProperties.Publish().apply {
            enabled = true
            context = "platform-ops"
            interval = java.time.Duration.ofSeconds(5)
        }
        publish.options().assert().isEqualTo(LedgerPublishing("platform-ops", 5.seconds))
    }

    @Test
    fun `a handler that fails twice recovers on its own after it parks, and the webhook hears it parked`() {
        val id = openAccount()
        eventually { failing.attempts(FLAKY, id) == 3 }
        eventually { host.get(entry(FLAKY, id)).status == 404 }
        failing.handled.assert().contains("$FLAKY:$id")
        val parked = hooks.filter {
            it["entry"]["aggregate"]["id"].asString() == id && it["entry"]["subscription"].asString() == FLAKY
        }
        parked.map { it["event"].asString() }.assert().containsExactly("PARKED")
        parked.single()["service"].asString().assert().isEqualTo("bank-service")
        parked.single()["entry"]["subscription"].asString().assert().isEqualTo(FLAKY)
    }

    @Test
    fun `an entry whose retries are spent waits for an operator, who acts on it over HTTP`() {
        val id = openAccount()
        eventually { hooks.any { it["event"].asString() == "EXHAUSTED" && it["entry"]["aggregate"]["id"].asString() == id } }
        failing.attempts(BROKEN, id).assert().isEqualTo(2)

        val listed = host.get("${OpsPaths.PARKED}?subscription=$BROKEN")
        listed.status.assert().isEqualTo(200)
        listed.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().contains(id)
        val exhausted = host.get(entry(BROKEN, id)).body!!
        exhausted["status"].asString().assert().isEqualTo("EXHAUSTED")
        exhausted["retries"].asInt().assert().isEqualTo(1)
        exhausted["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(1)

        val resumed = host.post("${entry(BROKEN, id)}/${OpsPaths.RESUME}", null)
        resumed.status.assert().isEqualTo(200)
        resumed.body!!["outcome"].asString().assert().isEqualTo("PARKED")
        failing.attempts(BROKEN, id).assert().isEqualTo(3)

        val marked = host.post(
            "${entry(BROKEN, id)}/${OpsPaths.MARK_RECOVERABLE}",
            """{"recoverable":"UNRECOVERABLE"}"""
        )
        marked.body!!["status"].asString().assert().isEqualTo("UNRECOVERABLE")
        host.post("${entry(BROKEN, id)}/${OpsPaths.MARK_RECOVERABLE}", """{"recoverable":"RECOVERABLE"}""")
        // A spec with another retry takes it up again; it still fails, and is spent again.
        val applied = host.post("${entry(BROKEN, id)}/${OpsPaths.APPLY_RETRY_SPEC}", """{"maxRetries":2}""")
        applied.status.assert().isEqualTo(200)
        applied.body!!["retrySpec"]["applied"].asBoolean().assert().isTrue()
        eventually { failing.attempts(BROKEN, id) == 4 }
        eventually {
            hooks.count { it["event"].asString() == "EXHAUSTED" && it["entry"]["aggregate"]["id"].asString() == id } == 2
        }

        val skipped = host.post("${entry(BROKEN, id)}/${OpsPaths.SKIP}", null)
        skipped.status.assert().isEqualTo(200)
        skipped.body!!["outcome"].asString().assert().isEqualTo("RESOLVED")
        host.get(entry(BROKEN, id)).status.assert().isEqualTo(404)
        host.post("${entry(BROKEN, id)}/${OpsPaths.RESUME}", null).status.assert().isEqualTo(404)
    }

    /** The application's two failing subscriptions of the bank's accounts. */
    @Configuration(proxyBeanMethods = false)
    class FailingSubscriptions {
        private val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val handled: MutableList<String> = CopyOnWriteArrayList()

        fun attempts(subscription: String, id: String): Int = attempts["$subscription:$id"]?.get() ?: 0

        /** The operator's actions are refused without an authorizer (review A3-3): this host allows them all. */
        @Bean
        fun operators(): CommandAuthorizer = CommandAuthorizer.ALLOW_ALL

        @Bean
        fun failingSubscriptionFactory(): SubscriptionFactory = SubscriptionFactory { context ->
            if (context.context != BANK) {
                emptyList()
            } else {
                listOf(subscription(FLAKY, failures = 2), subscription(BROKEN, failures = Int.MAX_VALUE))
            }
        }

        private fun subscription(name: String, failures: Int) = Subscription(name, SubscriptionFilter(BANK)) { entry ->
            val key = "$name:${entry.ref.id}"
            val attempt = attempts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            if (attempt <= failures) error("$name fails ${entry.ref.id} (attempt $attempt)")
            handled.add(key)
        }
    }

    private companion object {
        const val FLAKY = "HostOps.flaky"
        const val BROKEN = "HostOps.broken"
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
