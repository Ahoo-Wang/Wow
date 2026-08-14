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

package me.ahoo.wow.webflux.route.response

import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class StreamingJsonArrayResponseTest {
    @Test
    fun `first error should use normal HTTP error response`() {
        val exchange = exchange()
        response(Flux.error(IllegalArgumentException("bad")))
            .writeTo(exchange, CONTEXT)
            .test()
            .verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `post first error should leave array incomplete`() {
        val exchange = exchange()
        val response = response(Flux.just(Value("one")).concatWith(Mono.error { IllegalArgumentException("bad") }))

        response.writeTo(exchange, CONTEXT).test()
            .verifyError(IllegalArgumentException::class.java)
        val chunks = mutableListOf<String>()
        exchange.response.body
            .map { buffer -> buffer.toString(StandardCharsets.UTF_8) }
            .doOnNext(chunks::add)
            .test()
            .thenConsumeWhile { true }
            .verifyError(IllegalArgumentException::class.java)
        chunks.joinToString("").assert().startsWith("[").doesNotEndWith("]")
    }

    @Test
    fun `cancellation should cancel upstream`() {
        val cancelled = AtomicBoolean()
        val exchange = exchange()

        response(Flux.never<Value>().doOnCancel { cancelled.set(true) })
            .writeTo(exchange, CONTEXT)
            .test()
            .thenCancel()
            .verify()

        cancelled.get().assert().isTrue()
    }

    private fun response(body: Flux<Value>): StreamingJsonArrayResponse<Value> = StreamingJsonArrayResponse(
        body,
        MockServerRequest.builder().build(),
        WebFluxRequestExceptionHandler()
    )

    private fun exchange() = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

    private data class Value(val value: String)

    private companion object {
        val CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
