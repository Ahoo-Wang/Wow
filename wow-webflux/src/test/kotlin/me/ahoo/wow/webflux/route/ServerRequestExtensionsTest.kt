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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.server.NotAcceptableStatusException

class ServerRequestExtensionsTest {
    @Test
    fun `should accept parameterized event stream`() {
        val request = request(MediaType.TEXT_EVENT_STREAM_VALUE + ";q=0.9")

        request.acceptsEventStream().assert().isTrue()
    }

    @Test
    fun `should prefer event stream with higher quality`() {
        val request = request("application/json;q=0.1, text/event-stream;q=1")

        request.acceptsEventStream().assert().isTrue()
    }

    @Test
    fun `should reject event stream with zero quality`() {
        val request = request("text/event-stream;q=0, application/json;q=1")

        request.acceptsEventStream().assert().isFalse()
    }

    @Test
    fun `should keep json as wildcard default`() {
        val request = request(MediaType.ALL_VALUE)

        request.acceptsEventStream().assert().isFalse()
    }

    @Test
    fun `should honor a specific json rejection over wildcard`() {
        val request = request("application/json;q=0, */*;q=1")

        request.acceptsEventStream().assert().isTrue()
    }

    @Test
    fun `should honor a specific event stream rejection over wildcard`() {
        val request = request("text/event-stream;q=0, */*;q=1")

        request.acceptsEventStream().assert().isFalse()
    }

    @Test
    fun `should reject unsupported response media types`() {
        assertThrows<NotAcceptableStatusException> {
            request(MediaType.TEXT_PLAIN_VALUE).acceptsEventStream()
        }
    }

    @Test
    fun `should reject when every supported response type has zero quality`() {
        assertThrows<NotAcceptableStatusException> {
            request("*/*;q=1, application/json;q=0, text/event-stream;q=0").acceptsEventStream()
        }
    }

    private fun request(accept: String): MockServerRequest {
        return MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, accept)
            .build()
    }
}
