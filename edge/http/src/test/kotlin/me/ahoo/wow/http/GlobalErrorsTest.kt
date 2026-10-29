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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.http.problem.GlobalErrors
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import kotlin.time.Duration.Companion.milliseconds

/** The application's own routes answer what fails on them as the framework routes' problem+json (review M-3, D259). */
class GlobalErrorsTest {
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    private fun result(code: String, status: CommandResult.Status) = CommandResult(
        commandId = "c",
        requestId = "r",
        correlationId = "c",
        ref = ref,
        hop = Hop.committed(CONTEXT),
        function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "Checkout"),
        status = status,
        error = ErrorInfo(code, "No."),
        signalTime = 1,
    )

    private val routes = coRouter {
        GET("/app/rejected") { throw CommandRejectedException(result(CART_EMPTY, CommandResult.Status.REJECTED)) }
        GET("/app/busy") {
            throw WowProblemException("wow.command.busy", 429, RetryHint(true, true, 1500.milliseconds), "Busy.")
        }
        GET("/app/failed") {
            throw WowProblemException(
                "wow.command.failed",
                500,
                RetryHint.NONE,
                "Failed.",
                result("wow.command.failed", CommandResult.Status.FAILED),
            )
        }
        GET("/app/plain") { throw WowProblemException("wow.node.closing", 503, RetryHint.NONE, "Closing.") }
        GET("/app/unavailable") { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret upstream") }
        GET("/app/unknown") { throw ResponseStatusException(HttpStatusCode.valueOf(599), "odd") }
        GET("/app/large") { throw DataBufferLimitException("Exceeded limit on max bytes to buffer") }
        GET("/app/down") { throw StorageUnavailableException("The log is down.") }
        GET("/app/forbidden") { throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not yours.") }
        GET("/app/broken") { throw IllegalStateException("secret jdbc:postgresql://db-internal:5432") }
    }

    /** The routes served as a host serves them: its exception handlers sorted, this one first. */
    private val client = WebTestClient.bindToServer(
        HttpHandlerConnector(
            WebHttpHandlerBuilder.webHandler(RouterFunctions.toWebHandler(routes))
                .exceptionHandler(GlobalErrors(cartManifest()))
                .build(),
        ),
    ).build()

    private fun get(path: String): Answer = client.get().uri(path).answer()

    @Test
    fun `a command's rejection should be its result, with the status of its code`() {
        val answer = get("/app/rejected")
        // Its status in the context's table, as the framework routes answer it.
        answer.status.assert().isEqualTo(409)
        answer.contentType.assert().isEqualTo("application/problem+json")
        answer.body!!["code"].asString().assert().isEqualTo(CART_EMPTY)
        answer.body!!["type"].asString().assert().isEqualTo("urn:wow:error:$CART_EMPTY")
        answer.body!!["result"]["ref"]["id"].asString().assert().isEqualTo("c-1")
    }

    @Test
    fun `a Wow problem should keep its code, status, result and Retry-After`() {
        val busy = get("/app/busy")
        busy.status.assert().isEqualTo(429)
        busy.body!!["code"].asString().assert().isEqualTo("wow.command.busy")
        busy.headers.getFirst("Retry-After").assert().isEqualTo("2")
        val failed = get("/app/failed")
        failed.status.assert().isEqualTo(500)
        failed.body!!["result"]["status"].asString().assert().isEqualTo("failed")
    }

    @Test
    fun `a store that is down, an HTTP status and an unexpected failure should be the framework's problems`() {
        val down = get("/app/down")
        down.status.assert().isEqualTo(503)
        down.body!!["code"].asString().assert().isEqualTo("wow.storage.unavailable")
        down.headers.getFirst("Retry-After").assert().isEqualTo("1")
        val forbidden = get("/app/forbidden")
        forbidden.status.assert().isEqualTo(403)
        forbidden.body!!["type"].asString().assert().isEqualTo("about:blank")
        forbidden.body!!["detail"].asString().assert().isEqualTo("Not yours.")
        val missing = get("/app/none")
        missing.status.assert().isEqualTo(404)
        missing.contentType.assert().isEqualTo("application/problem+json")
        val broken = get("/app/broken")
        broken.status.assert().isEqualTo(500)
        broken.body.toString().assert().doesNotContain("jdbc")
    }

    @Test
    fun `a problem without a result or Retry-After, a 5xx status, an unknown one and a large body should map too`() {
        val plain = get("/app/plain")
        plain.status.assert().isEqualTo(503)
        plain.body!!["code"].asString().assert().isEqualTo("wow.node.closing")
        plain.headers.getFirst("Retry-After").assert().isNull()
        val unavailable = get("/app/unavailable")
        unavailable.status.assert().isEqualTo(503)
        unavailable.body!!.has("detail").assert().isFalse()
        get("/app/unknown").status.assert().isEqualTo(500)
        val large = get("/app/large")
        large.status.assert().isEqualTo(413)
        large.contentType.assert().isEqualTo("application/problem+json")
    }

    @Test
    fun `it should run before Spring Boot's error handler, and leave a committed response as it is`() {
        val errors = GlobalErrors(cartManifest())
        errors.order.assert().isLessThan(-1)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/app"))
        exchange.response.setComplete().block()
        val failure = IllegalStateException("after the response")
        runCatching { errors.handle(exchange, failure).block() }.exceptionOrNull().assert().isSameAs(failure)
    }
}
