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
import me.ahoo.wow.kernel.FailureReasons
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.runtime.subscription.ParkedNotice
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
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

    private val spec = ParkedRetrySpec(maxRetries = 10, initialBackoff = 3.minutes, claimTtl = 2.minutes)
    private val entry = ParkedAggregate(
        SubscriptionId("shop", "CartSaga.onOrderCreated"),
        AggregateType("shop", "cart").ref("t", "c-1"),
        0,
        2,
        "java.lang.IllegalStateException: down",
        FAILED_AT,
        fromPosition = 42,
    )
    private val parked = ParkedNotice(ParkedNotice.Kind.PARKED, "shop-service.shop", entry, spec, FAILED_AT)

    data class Card(val holder: String, val digits: Int)

    private fun failureOf(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull()!!

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
    fun `a failure on a value should leave the service without the value, by either format and the ops routes`(): Unit =
        runBlocking {
            // #6588: the probe's cases, a card number or an address where a number goes, with the reasons they park with.
            val failures = listOf(
                failureOf { WowJson.mapper.treeToValue(WowJson.mapper.readTree(CARD), Card::class.java) },
                failureOf {
                    val version = ENVELOPE.replaceFirst("\"version\":1", "\"version\":\"$NUMBER\"")
                    EnvelopeCodec.stateEventOf(version.toByteArray())
                },
                failureOf {
                    val commitTime = ENVELOPE.replace("\"commitTime\":1000", "\"commitTime\":\"$ADDRESS\"")
                    EnvelopeCodec.stateEventOf(commitTime.toByteArray())
                },
            )
            failures.forEach { failure ->
                val reasoned = entry.copyWith(reason = FailureReasons.DEFAULT.reasonOf(failure))
                WebhookNotifier(uri).notify(parked.copyWith(entry = reasoned))
                WebhookNotifier(uri, WebhookNotifier.Format.WECOM).notify(parked.copyWith(entry = reasoned))
                json.writeValueAsString(viewOf(ParkedView(reasoned, spec, "shop-service.shop"))).assert()
                    .doesNotContain(NUMBER, ADDRESS)
            }
            received.map { it.second }.assert().hasSize(6).allSatisfy { body ->
                body.assert().contains("MismatchedInputException: cannot read").doesNotContain(NUMBER, ADDRESS)
            }
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
        const val NUMBER = "4111 1111 1111 1111"
        const val ADDRESS = "alice.smith@example.com"
        const val CARD = """{"holder":"Alice Smith","digits":"$NUMBER"}"""

        /** A state event's envelope, as a topic holds it. */
        const val ENVELOPE = """{"format":1,"kind":"state-event","id":"shop.cart/t/c-1@1","type":"shop.cart",""" +
            """"payload":{"ref":{"type":{"context":"shop","name":"cart"},"tenantId":"t","id":"c-1"},"version":1,""" +
            """"commandId":"c","requestId":"r","commandType":"shop.cart.Open","commitTime":1000,""" +
            """"events":[{"name":"shop.cart.Opened","revision":1,"body":{}}],"state":null,""" +
            """"scope":{"ownerId":null,"spaceId":null},"deleted":false,"access":null,"accessVersion":null,""" +
            """"causation":null,"headers":{},"operator":null},"transport":null}"""
    }
}
