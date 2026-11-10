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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.runtime.wait.HeldDeadlines
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import java.time.Duration

/** `Accept: text/event-stream` on a command route (decision D101, design 04 §6.1). */
class EventStreamTest {
    private val node = HttpNode()

    private fun stream(path: String, body: String, headers: Map<String, String> = emptyMap(), node: HttpNode = this.node) =
        node.client.post().uri(path)
            .header("X-User", "alice")
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    /** The events of a stream, each one seen by [seen] as it arrives. */
    private fun events(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        node: HttpNode = this.node,
        seen: (ServerSentEvent<String>) -> Unit = {},
    ) = stream(path, body, headers, node)
        .expectStatus().isOk
        .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
        .returnResult(EVENT)
        .responseBody
        .doOnNext(seen)
        .collectList()
        .block(Duration.ofSeconds(10))!!

    /**
     * The events of a command that waits for its commit, then an audit that never answers: the deadline passes once
     * the commit's event arrived, however long the commit took.
     */
    private fun timedOutAfterCommit(): List<ServerSentEvent<String>> {
        val held = HeldDeadlines()
        return events(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            mapOf(WowHeaders.WAIT to "committed > handled(audit)"),
            HttpNode(deadlines = held),
        ) { if (it.event() == "committed") held.pass() }
    }

    @Test
    fun `a decide function that throws ends the stream with its failed result`() {
        node.client.post().uri("/shop/cart/c-1/open-cart").header("X-User", "alice")
            .header("Content-Type", "application/json").bodyValue("{}").exchange().expectStatus().isOk
        val events = events("/shop/cart/c-1/checkout", """{"note":"throw"}""")
        val last = json.readTree(events.last().data())
        last["status"].asString().assert().isEqualTo("failed")
        last["error"]["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        events.last().data().assert().doesNotContain("secret")
    }

    @Test
    fun `each hop reached is an event, until the wait ends`() {
        val events = timedOutAfterCommit()
        events.map { it.id() }.assert().containsExactly("0", "1")
        events.map { it.event() }.assert().containsExactly("committed", "committed")
        val committed = json.readTree(events.first().data())
        committed["status"].asString().assert().isEqualTo("succeeded")
        committed["version"].asInt().assert().isEqualTo(1)
        committed["function"]["kind"].asString().assert().isEqualTo("command")
    }

    @Test
    fun `a rejection after admission is the event of its hop, in a 200 stream`() {
        node.post("/shop/cart/c-1/open-cart").status.assert().isEqualTo(200)
        val events = events("/shop/cart/c-1/checkout", "{}")
        events.map { it.event() }.assert().containsExactly("committed")
        val rejected = json.readTree(events.last().data())
        rejected["status"].asString().assert().isEqualTo("rejected")
        rejected["error"]["code"].asString().assert().isEqualTo(CART_EMPTY)
    }

    @Test
    fun `a wait that times out ends the stream with the timeout`() {
        val events = timedOutAfterCommit()
        events.size.assert().isEqualTo(2)
        json.readTree(events.last().data())["error"]["code"].asString().assert().isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
    }

    @Test
    fun `an admission refusal is answered as a problem, not as a stream`() {
        val problem = stream("/shop/cart/c-1/add-item", """{"quantity":1}""")
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let(json::readTree)
        problem["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        problem["result"]["hop"]["stage"].asString().assert().isEqualTo("accepted")
        // So is a wait the runtime cannot follow.
        stream("/shop/cart/c-1/add-item", """{"sku":"apple"}""", mapOf(WowHeaders.WAIT to "snapshot"))
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `a plan that ends at ACCEPTED is one event`() {
        val events = events("/shop/cart/c-1/add-item", """{"sku":"apple"}""", mapOf(WowHeaders.WAIT to "accepted"))
        events.map { it.event() }.assert().containsExactly("accepted")
    }

    private companion object {
        val EVENT = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
