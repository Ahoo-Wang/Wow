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

import kotlinx.coroutines.CompletableDeferred
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Review r1 (the HTTP edge, OpenAPI and the client): what the public port promises about a request's headers and path,
 * and about when a command's event stream answers.
 */
class R1HttpEdgeTest {
    private val node = HttpNode()

    @Test
    fun `a wait hop left open is refused at once, whatever white space it holds`() {
        // `accepted(`, white space, and no `)`: the hop pattern's \s*, [^()]*? and \s* share the spaces, and
        // java.util.regex backtracks over them about n³/6 times before it fails (WaitHeader.HOP).
        val open = "accepted(" + " ".repeat(1500) + "x"
        val started = TimeSource.Monotonic.markNow()
        val answer = node.post(
            "/shop/cart/c-1/add-item",
            """{"sku":"apple"}""",
            headers = mapOf("Wow-Wait" to open),
        )
        val took = started.elapsedNow()
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.WAIT_INVALID)
        took.assert().describedAs("the parse of a 1,500-space hop").isLessThan(1.seconds) // fails: seconds of CPU
    }

    @Test
    fun `a path segment with an empty matrix parameter is refused, not routed without it`() {
        // `c-1;` and `c-1;=v` carry matrix parameters with no name: Spring cuts the segment at `;` and lists none,
        // so the refusal of matrix parameters (PublicRequests) passes them, and the command reaches c-1.
        listOf("/shop/cart/c-1;/add-item", "/shop/cart/c-1;=v/add-item").forEach { path ->
            val answer = node.post(path, """{"sku":"apple"}""")
            answer.status.assert().describedAs("the status of $path").isEqualTo(400) // fails: 200 on c-1
            answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        }
    }

    @Test
    fun `a command's event stream answers 200 once admitted, before the hop its plan waits for`() {
        // reference/http.md: an event stream "answers 200 once admitted, then one event per result as the wait goes on".
        val held = CompletableDeferred<Unit>()
        node.appendGate = held
        try {
            val answered = CompletableFuture.supplyAsync {
                node.client.post().uri("/shop/cart/c-1/open-cart")
                    .header("X-User", "alice")
                    .header("Wow-Wait", "accepted > committed")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .returnResult(String::class.java)
                    .status
                    .value()
            }
            // The append waits: the command is admitted and accepted, and nothing has committed.
            val whileHeld = runCatching { answered.get(3, TimeUnit.SECONDS) }.getOrNull()
            held.complete(Unit)
            // The harness answers the stream once the append goes on: the status it reads is the stream's.
            answered.get(10, TimeUnit.SECONDS).assert().isEqualTo(200)
            whileHeld.assert().describedAs("the stream's status while its command has not committed")
                .isEqualTo(200) // fails: no answer until the commit, the hop accepted never streamed
        } finally {
            held.complete(Unit)
        }
    }
}
