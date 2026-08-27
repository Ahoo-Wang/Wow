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
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class WebFluxResponseStrategyTest {

    @Test
    fun `json array response should be created immediately for never flux`() {
        DefaultWebFluxResponseStrategy
            .jsonArray(Flux.never<String>(), MockServerRequest.builder().build(), WebFluxRequestExceptionHandler())
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
                it.cookies().assert().isEmpty()
            }
            .verifyComplete()
    }

    @Test
    fun `json array response should write finite flux as json array`() {
        DefaultWebFluxResponseStrategy
            .jsonArray(
                Flux.just(BodyValue("one"), BodyValue("two"), BodyValue("three")),
                MockServerRequest.builder().build(),
                WebFluxRequestExceptionHandler()
            )
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
                it.writeBody().assert().isEqualTo("""[{"name":"one"},{"name":"two"},{"name":"three"}]""")
            }
            .verifyComplete()
    }

    @Test
    fun `json array response should write empty flux as empty json array`() {
        DefaultWebFluxResponseStrategy
            .jsonArray(Flux.empty<BodyValue>(), MockServerRequest.builder().build(), WebFluxRequestExceptionHandler())
            .test()
            .consumeNextWith {
                it.writeBody().assert().isEqualTo("[]")
            }
            .verifyComplete()
    }

    @Test
    fun `json array response should delegate first error before writing success response`() {
        DefaultWebFluxResponseStrategy
            .jsonArray(
                Flux.error<BodyValue>(IllegalArgumentException("bad")),
                MockServerRequest.builder().build(),
                WebFluxRequestExceptionHandler()
            )
            .test()
            .consumeNextWith {
                val exchange = it.writeToExchange()
                exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
                exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
                exchange.response.bodyAsString.block()!!.assert().doesNotContain("""[{"name"""")
            }
            .verifyComplete()
    }

    @Test
    fun `json array response should propagate error after first value`() {
        val response = DefaultWebFluxResponseStrategy
            .jsonArray(
                Flux.just(BodyValue("one")).concatWith(Mono.error<BodyValue> { IllegalArgumentException("bad") }),
                MockServerRequest.builder().build(),
                WebFluxRequestExceptionHandler()
            )
            .block()!!

        response.writeToExchangeExpectingError()
    }

    @Test
    fun `sse response should keep event stream content type`() {
        val request = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()

        DefaultWebFluxResponseStrategy
            .sse(Flux.empty(), request, WebFluxRequestExceptionHandler())
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.TEXT_EVENT_STREAM)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }

    @Test
    fun `sse response should write error event`() {
        val request = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()

        DefaultWebFluxResponseStrategy
            .sse(
                Flux.error<ServerSentEvent<String>>(IllegalArgumentException("bad")),
                request,
                WebFluxRequestExceptionHandler()
            )
            .test()
            .consumeNextWith {
                val body = it.writeBody()
                body.assert().contains(ErrorCodes.ILLEGAL_ARGUMENT)
                body.assert().contains("data:")
            }
            .verifyComplete()
    }

    private data class BodyValue(val name: String)

    private fun ServerResponse.writeBody(): String {
        val exchange = writeToExchange()
        return exchange.response.bodyAsString.block()!!
    }

    private fun ServerResponse.writeToExchange(): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").build()
        )
        writeTo(exchange, EMPTY_CONTEXT)
            .test()
            .verifyComplete()
        return exchange
    }

    private fun ServerResponse.writeToExchangeExpectingError(): MockServerWebExchange {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").build()
        )
        var error: Throwable? = null
        try {
            writeTo(exchange, EMPTY_CONTEXT)
                .block()
        } catch (throwable: Throwable) {
            error = throwable
        }
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        return exchange
    }

    private companion object {
        private val EMPTY_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
