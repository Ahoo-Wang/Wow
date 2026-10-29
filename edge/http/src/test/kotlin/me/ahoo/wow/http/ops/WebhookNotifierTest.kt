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

package me.ahoo.wow.http.ops

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.json
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The webhook of parked entries (roadmap P.1), against a stub server on the loopback interface. */
class WebhookNotifierTest {
    private val received = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()
    private val status = AtomicInteger(200)
    private val answer = AtomicReference("""{"errcode":0,"errmsg":"ok"}""")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            val headers = exchange.requestHeaders.mapValues { it.value.joinToString() }
            received += headers to exchange.requestBody.readAllBytes().decodeToString()
            val bytes = answer.get().toByteArray()
            exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val uri = URI.create("http://127.0.0.1:${server.address.port}/hook")

    private val spec = ParkedRetrySpec(maxRetries = 10, initialBackoff = 3.minutes, timeout = 2.minutes)
    private val entry = ParkedAggregate(
        "CartSaga.onOrderCreated",
        AggregateType("shop", "cart").ref("t", "c-1"),
        0,
        2,
        "java.lang.IllegalStateException: down",
        FAILED_AT,
        fromPosition = 42,
    )
    private val parked = ParkedNotice(ParkedNotice.Kind.PARKED, "shop-service.shop", "shop", entry, spec, FAILED_AT)

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `a notice is posted as Wow's JSON with its entry and a summary`(): Unit = runBlocking {
        WebhookNotifier(uri, service = "shop-service", headers = mapOf("X-Token" to "secret")).notify(parked)
        val (headers, body) = received.single()
        headers.getValue("Content-type").assert().isEqualTo("application/json")
        headers.getValue("X-token").assert().isEqualTo("secret")
        val posted = json.readTree(body)
        posted["event"].asString().assert().isEqualTo("parked")
        posted["service"].asString().assert().isEqualTo("shop-service")
        posted["group"].asString().assert().isEqualTo("shop-service.shop")
        posted["at"].asString().assert().isEqualTo("2023-11-14T22:13:20Z")
        posted["entry"]["subscription"].asString().assert().isEqualTo("shop.CartSaga.onOrderCreated")
        posted["entry"]["aggregate"]["id"].asString().assert().isEqualTo("c-1")
        posted["entry"]["fromPosition"].asLong().assert().isEqualTo(42L)
        posted["entry"]["nextRetryAt"].asLong().assert().isEqualTo(FAILED_AT + 3.minutes.inWholeMilliseconds)
        val text = posted["text"].asString()
        text.assert().contains("**shop.CartSaga.onOrderCreated parked: shop.cart/t/c-1**")
        text.assert().contains("> service: shop-service")
        text.assert().contains("> from version: 2, position 42")
        text.assert().contains("> reason: java.lang.IllegalStateException: down")
        text.assert().contains("> retries: 0 of 10, next retry at 2023-11-14T22:16:20Z")
    }

    @Test
    fun `a notice is posted as a WeCom robot's Markdown message`(): Unit = runBlocking {
        val exhausted = parked.copyWith(kind = ParkedNotice.Kind.EXHAUSTED, entry = entry.copyWith(retries = 10))
        WebhookNotifier(uri, WebhookNotifier.Format.WECOM).notify(exhausted)
        val posted = json.readTree(received.single().second)
        posted["msgtype"].asString().assert().isEqualTo("markdown")
        val content = posted["markdown"]["content"].asString()
        content.assert().contains("spent its retries")
        content.assert().contains("> retries: 10 of 10, no automatic retry left")
        content.assert().doesNotContain("> service:")
    }

    @Test
    fun `a refusal fails the notification`(): Unit = runBlocking {
        status.set(500)
        answer.set("down")
        assertThrownBy<IllegalStateException> { runBlocking { WebhookNotifier(uri).notify(parked) } }
        status.set(200)
        answer.set("""{"errcode":93000,"errmsg":"invalid webhook url"}""")
        assertThrownBy<IllegalStateException> {
            runBlocking { WebhookNotifier(uri, WebhookNotifier.Format.WECOM).notify(parked) }
        }
        // A generic hook's answer is not read; nor is a robot's that is not JSON.
        WebhookNotifier(uri).notify(parked)
        answer.set("ok")
        WebhookNotifier(uri, WebhookNotifier.Format.WECOM).notify(parked)
        received.size.assert().isEqualTo(4)
    }

    @Test
    fun `only the kinds asked for are posted`(): Unit = runBlocking {
        val notifier = WebhookNotifier(uri, kinds = setOf(ParkedNotice.Kind.EXHAUSTED), timeout = 2.seconds)
        notifier.notify(parked)
        received.assert().isEmpty()
        notifier.notify(parked.copyWith(kind = ParkedNotice.Kind.EXHAUSTED))
        received.size.assert().isEqualTo(1)
        notifier.toString().assert().isEqualTo("WebhookNotifier(GENERIC, $uri)")
    }

    @Test
    fun `a webhook is an http URL with a positive timeout`() {
        assertThrownBy<IllegalArgumentException> { WebhookNotifier(URI.create("ftp://127.0.0.1/hook")) }
        assertThrownBy<IllegalArgumentException> { WebhookNotifier(uri, timeout = 0.seconds) }
    }

    private companion object {
        const val FAILED_AT = 1_700_000_000_000L
    }
}
