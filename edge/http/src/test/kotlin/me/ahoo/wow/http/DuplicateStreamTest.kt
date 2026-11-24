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
import me.ahoo.wow.api.wire.WowHeaders
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import java.time.Duration

/** A retry streamed with a plan past its commit, of a command that committed: the event stream on the direct path. */
class DuplicateStreamTest {
    private val node = HttpNode()

    @Test
    fun `a duplicate's stream under a plan past its commit should carry its commit once`() {
        val request = mapOf(WowHeaders.REQUEST_ID to "req-1")
        node.client.post().uri("/shop/cart/c-1/add-item").header("X-User", "alice")
            .headers { http -> request.forEach { (name, value) -> http.set(name, value) } }
            .header("Content-Type", "application/json").bodyValue("""{"sku":"apple"}""").exchange()
            .expectStatus().isOk
        val events = node.client.post().uri("/shop/cart/c-1/add-item")
            .header("X-User", "alice")
            .header(WowHeaders.REQUEST_ID, "req-1")
            .header(WowHeaders.WAIT, "committed > handled(audit)")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"sku":"apple"}""")
            .exchange()
            .expectStatus().isOk
            .returnResult(EVENT)
            .responseBody
            .filter { it.event() != null }
            .collectList()
            .block(Duration.ofSeconds(10))!!
        // The duplicate's commit ends the wait (#6775): one result, at committed.
        events.map { it.event() }.assert().containsExactly("committed") // fails: [committed, committed]
    }

    private companion object {
        val EVENT = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
