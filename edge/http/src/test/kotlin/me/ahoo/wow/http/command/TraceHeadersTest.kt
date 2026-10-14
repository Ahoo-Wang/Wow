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

package me.ahoo.wow.http.command

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest

/** The W3C trace context a command request carries to admission (decision D174). */
class TraceHeadersTest {
    @Test
    fun `a request should carry its traceparent and the headers that go with it, nothing without one`() {
        val parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        CommandHandler.traceOf(
            MockServerRequest.builder()
                .header("traceparent", parent)
                .header("tracestate", "wow=1")
                .header("baggage", "tenant=acme")
                .build(),
        ).assert().isEqualTo(mapOf("traceparent" to parent, "tracestate" to "wow=1", "baggage" to "tenant=acme"))
        CommandHandler.traceOf(MockServerRequest.builder().header("traceparent", parent).build())
            .assert().isEqualTo(mapOf("traceparent" to parent))
        CommandHandler.traceOf(MockServerRequest.builder().header("tracestate", "wow=1").build()).assert().isEmpty()
    }
}
