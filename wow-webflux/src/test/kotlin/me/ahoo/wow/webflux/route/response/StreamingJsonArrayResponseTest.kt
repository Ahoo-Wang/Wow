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
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Publisher
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchangeDecorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class StreamingJsonArrayResponseTest {
    @Test
    fun `fatal errors are never converted to HTTP or incomplete query errors`() {
        val first = OutOfMemoryError("first")
        assertThrows<OutOfMemoryError> {
            response(Flux.error<Value>(first)).writeTo(exchange(), CONTEXT).block()
        }.assert().isSameAs(first)

        val later = OutOfMemoryError("later")
        assertThrows<OutOfMemoryError> {
            response(Flux.just(Value("one")).concatWith(Mono.error(later))).writeTo(exchange(), CONTEXT).block()
        }.assert().isSameAs(later)
    }

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
            .consumeErrorWith(::assertIncompleteResult)
            .verify()
        val chunks = mutableListOf<String>()
        exchange.response.body
            .map { buffer -> buffer.toString(StandardCharsets.UTF_8) }
            .doOnNext(chunks::add)
            .test()
            .thenConsumeWhile { true }
            .consumeErrorWith(::assertIncompleteResult)
            .verify()
        chunks.joinToString("").assert().startsWith("[").doesNotEndWith("]")
    }

    @Test
    fun `empty stream should write an empty JSON array on the wire`() {
        val exchange = exchange()

        response(Flux.empty<Value>()).writeTo(exchange, CONTEXT).test().verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
        exchange.response.bodyAsString.block().assert().isEqualTo("[]")
    }

    @Test
    fun `first item serialization failure should use normal HTTP error response`() {
        val exchange = exchange()

        response(Flux.just(ExplodingValue())).writeTo(exchange, CONTEXT).test().verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.INTERNAL_SERVER_ERROR)
        exchange.response.bodyAsString.block().assert().contains("Unexpected server error")
    }

    @Test
    fun `second item serialization failure should be incomplete and omit closing bracket`() {
        val exchange = exchange()

        response(Flux.just(Value("one"), ExplodingValue()))
            .writeTo(exchange, CONTEXT)
            .test()
            .consumeErrorWith(::assertIncompleteResult)
            .verify()

        val chunks = mutableListOf<String>()
        exchange.response.body
            .map { buffer -> buffer.toString(StandardCharsets.UTF_8) }
            .doOnNext(chunks::add)
            .test()
            .thenConsumeWhile { true }
            .consumeErrorWith(::assertIncompleteResult)
            .verify()
        chunks.joinToString("").assert().startsWith("[").contains("one").doesNotEndWith("]")
    }

    @Test
    fun `writer failure after first item should be incomplete and cancel upstream`() {
        val cancelled = AtomicBoolean()
        val delegate = exchange()
        val failingResponse = object : ServerHttpResponseDecorator(delegate.response) {
            override fun writeWith(body: Publisher<out org.springframework.core.io.buffer.DataBuffer>): Mono<Void> =
                Flux.from(body).take(1).then(Mono.error(IOException("write failed")))
        }
        val exchange = object : ServerWebExchangeDecorator(delegate) {
            override fun getResponse(): ServerHttpResponse = failingResponse
        }
        val body = Flux.concat(Flux.just(Value("one")), Flux.never<Value>())
            .doOnCancel { cancelled.set(true) }

        response(body).writeTo(exchange, CONTEXT)
            .test()
            .consumeErrorWith(::assertIncompleteResult)
            .verify()

        cancelled.get().assert().isTrue()
    }

    @Test
    fun `cancellation after first item should cancel upstream`() {
        val cancelled = AtomicBoolean()
        val exchange = exchange()
        val body = Flux.concat(Flux.just(Value("one")), Flux.never<Value>())
            .doOnCancel { cancelled.set(true) }

        response(body).writeTo(exchange, CONTEXT)
            .test()
            .thenCancel()
            .verify()

        cancelled.get().assert().isTrue()
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

    private fun <T : Any> response(body: Flux<T>): StreamingJsonArrayResponse<T> = StreamingJsonArrayResponse(
        body,
        MockServerRequest.builder().build(),
        WebFluxRequestExceptionHandler()
    )

    private fun exchange() = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

    private data class Value(val value: String)

    private class ExplodingValue {
        val value: String
            get() = throw IllegalStateException("serialize failed")
    }

    private fun assertIncompleteResult(error: Throwable) {
        (error as QueryException).let {
            it.code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
            it.stage.assert().isEqualTo(QueryStage.EXECUTION)
            it.reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
        }
    }

    private companion object {
        val CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
