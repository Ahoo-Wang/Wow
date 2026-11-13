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

package me.ahoo.wow.spring.boot.http

import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.spring.boot.WowProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import kotlin.coroutines.ContinuationInterceptor

/**
 * The forwarded headers of a public request as its handler sees them, through the filter Wow registers
 * ([NodePeers.wowForwardedHeadersFilter]) and a `coRouter` handler, as Wow's routes are: the request's configured
 * headers, by their configured names, in the [ForwardedHeaders] of the handler's coroutine context, which
 * [UserCredential.FORWARDED] reads; none, and no element, for a request without them. A filter after it that runs in a
 * coroutine (`CoWebFilter`) sees the same element, and so does an application's annotated controller with a suspend
 * handler, which Spring starts from the same context (`InvocableHandlerMethod`).
 */
class ForwardedHeadersFilterTest {
    /** What a handler, and a coroutine filter after Wow's, see of the request's forwarded headers. */
    class Seen {
        var handler: Map<String, String>? = null
        var credential: Map<String, String>? = null
        var dispatcher: String? = null
        var laterFilter: Map<String, String>? = null
    }

    /** An application's own controller, annotated, with a suspend handler that would call a user-mode client. */
    @RestController
    class ProbeController(private val seen: Seen) {
        @GetMapping("/probe")
        suspend fun probe(): String {
            val context = currentCoroutineContext()
            seen.handler = context[ForwardedHeaders]?.headers
            seen.credential = UserCredential.FORWARDED.headers(Caller.ANONYMOUS)
            seen.dispatcher = context[ContinuationInterceptor].toString()
            return "ok"
        }
    }

    private fun filter(names: List<String>): WebFilter {
        val properties = WowProperties().apply { peer.forwardedHeaders = names.toMutableList() }
        return NodePeers().wowForwardedHeadersFilter(properties)
    }

    private fun laterFilter(seen: Seen): WebFilter = object : CoWebFilter() {
        override suspend fun filter(exchange: ServerWebExchange, chain: CoWebFilterChain) {
            seen.laterFilter = currentCoroutineContext()[ForwardedHeaders]?.headers
            chain.filter(exchange)
        }
    }

    private fun controllerClient(names: List<String>, seen: Seen): WebTestClient =
        WebTestClient.bindToController(ProbeController(seen))
            .webFilter<WebTestClient.ControllerSpec>(filter(names), laterFilter(seen))
            .build()

    private fun client(names: List<String>, seen: Seen): WebTestClient {
        val routes = coRouter {
            GET("/probe") {
                val context = currentCoroutineContext()
                seen.handler = context[ForwardedHeaders]?.headers
                seen.credential = UserCredential.FORWARDED.headers(Caller.ANONYMOUS)
                seen.dispatcher = context[ContinuationInterceptor].toString()
                ServerResponse.ok().bodyValueAndAwait("ok")
            }
        }
        return WebTestClient.bindToRouterFunction(routes)
            .webFilter<WebTestClient.RouterFunctionSpec>(filter(names), laterFilter(seen))
            .build()
    }

    @Test
    fun `the default Authorization should reach the handler and a later coroutine filter`() {
        val seen = Seen()
        client(listOf("Authorization"), seen).get().uri("/probe").header("Authorization", "Bearer alice")
            .exchange().expectStatus().isOk
        seen.handler.assert().isEqualTo(mapOf("Authorization" to "Bearer alice"))
        seen.credential.assert().isEqualTo(mapOf("Authorization" to "Bearer alice"))
        seen.laterFilter.assert().isEqualTo(mapOf("Authorization" to "Bearer alice"))
        // The handler runs unconfined, as coRouter starts it with no filter's context.
        seen.dispatcher.assert().isEqualTo("Dispatchers.Unconfined")
    }

    @Test
    fun `a custom list should forward each header the request carries, by its configured name, the first value`() {
        val seen = Seen()
        client(listOf("Authorization", "CoSec-App-Id"), seen).get().uri("/probe")
            .header("authorization", "Bearer bob", "Bearer second")
            .header("CoSec-App-Id", "shop")
            .exchange().expectStatus().isOk
        seen.handler.assert().isEqualTo(mapOf("Authorization" to "Bearer bob", "CoSec-App-Id" to "shop"))
        seen.credential.assert().isEqualTo(seen.handler)

        val one = Seen()
        client(listOf("Authorization", "CoSec-App-Id"), one).get().uri("/probe").header("CoSec-App-Id", "shop")
            .exchange().expectStatus().isOk
        one.handler.assert().isEqualTo(mapOf("CoSec-App-Id" to "shop"))
    }

    @Test
    fun `a request without them should reach the handler with no ForwardedHeaders, and the credential forward nothing`() {
        val seen = Seen()
        client(listOf("Authorization"), seen).get().uri("/probe").exchange().expectStatus().isOk
        seen.handler.assert().isNull()
        seen.credential.assert().isEqualTo(emptyMap<String, String>())
        seen.laterFilter.assert().isNull()
        // The handler's coroutine runs as coRouter starts it with no context of a filter's: unconfined.
        seen.dispatcher.assert().isEqualTo("Dispatchers.Unconfined")
    }

    @Test
    fun `an annotated controller's suspend handler should read the forwarded credential`() {
        val seen = Seen()
        controllerClient(listOf("Authorization", "CoSec-App-Id"), seen).get().uri("/probe")
            .header("Authorization", "Bearer alice")
            .header("CoSec-App-Id", "shop")
            .exchange().expectStatus().isOk
        seen.handler.assert().isEqualTo(mapOf("Authorization" to "Bearer alice", "CoSec-App-Id" to "shop"))
        seen.credential.assert().isEqualTo(seen.handler)
        seen.laterFilter.assert().isEqualTo(seen.handler)
        seen.dispatcher.assert().isEqualTo("Dispatchers.Unconfined")
    }

    @Test
    fun `an annotated controller's suspend handler should read nothing for a request without them`() {
        val seen = Seen()
        controllerClient(listOf("Authorization"), seen).get().uri("/probe").exchange().expectStatus().isOk
        seen.handler.assert().isNull()
        seen.credential.assert().isEqualTo(emptyMap<String, String>())
        seen.laterFilter.assert().isNull()
        seen.dispatcher.assert().isEqualTo("Dispatchers.Unconfined")
    }

    @Test
    fun `a request without them should pass untouched, no coroutine context set for it`() {
        val filter = NodePeers().wowForwardedHeadersFilter(WowProperties())
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build())
        filter.filter(exchange) { Mono.empty() }.block()
        exchange.attributes.assert().doesNotContainKey(CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE)
    }

    @Test
    fun `no configured name should forward nothing, whatever the request carries`() {
        val seen = Seen()
        client(emptyList(), seen).get().uri("/probe").header("Authorization", "Bearer alice")
            .exchange().expectStatus().isOk
        seen.handler.assert().isNull()
        seen.credential.assert().isEqualTo(emptyMap<String, String>())
    }
}
