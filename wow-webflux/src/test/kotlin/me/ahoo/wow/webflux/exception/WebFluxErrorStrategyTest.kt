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

package me.ahoo.wow.webflux.exception

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.bi.BiDeploymentInspectionException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.ErrorInfoConverter
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.BindException
import org.springframework.validation.FieldError
import org.springframework.web.reactive.function.server.RequestPredicates.POST
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI
import java.nio.charset.StandardCharsets

class WebFluxErrorStrategyTest {
    @BeforeEach
    fun resetHttpStatusMapping() {
        ErrorHttpStatusMapping.register(ErrorCodes.ILLEGAL_ARGUMENT, HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `should map throwable to functional server response`() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .uri(URI.create("/test"))
            .build()

        DefaultWebFluxErrorStrategy.toServerResponse(request, IllegalArgumentException("bad"))
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
            }
            .verifyComplete()
    }

    @Test
    fun `should map an unclassified throwable to a safe internal server response`() {
        val failure = NullPointerException("sensitive implementation detail")

        WebTestClient.bindToRouterFunction(
            route(POST("/test")) { request ->
                DefaultWebFluxErrorStrategy.toServerResponse(request, failure)
            }
        ).build()
            .post()
            .uri("/test")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.INTERNAL_SERVER_ERROR)
            .expectBody(String::class.java)
            .consumeWith { result ->
                result.responseBody!!.assert()
                    .contains(ErrorCodes.INTERNAL_SERVER_ERROR, "Unexpected server error")
                    .doesNotContain("sensitive implementation detail")
            }
    }

    @Test
    fun `should honor a registered converter for an otherwise unclassified throwable`() {
        val errorCode = "CUSTOM_WEBFLUX_FAILURE"
        val previous = ErrorInfoConverterRegistrar.register(
            RegisteredWebFluxFailure::class.java,
            ErrorInfoConverter<Throwable> { ErrorInfo.of(errorCode, "mapped failure") },
        )
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .uri(URI.create("/test"))
            .build()

        try {
            DefaultWebFluxErrorStrategy.toServerResponse(request, RegisteredWebFluxFailure())
                .test()
                .consumeNextWith { response ->
                    response.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
                    response.headers().getFirst(ERROR_CODE).assert().isEqualTo(errorCode)
                }
                .verifyComplete()
        } finally {
            ErrorInfoConverterRegistrar.unregister(RegisteredWebFluxFailure::class.java)
            previous?.let { converter ->
                ErrorInfoConverterRegistrar.register(RegisteredWebFluxFailure::class.java, converter)
            }
        }
    }

    @Test
    fun `should map BI inspection failures to upstream HTTP statuses`() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .uri(URI.create("/wow/bi/script"))
            .build()
        val cases = mapOf(
            BiDeploymentInspectionException.Inconsistent("inconsistent") to HttpStatus.BAD_GATEWAY,
            BiDeploymentInspectionException.Unavailable() to HttpStatus.SERVICE_UNAVAILABLE,
            BiDeploymentInspectionException.Timeout() to HttpStatus.GATEWAY_TIMEOUT,
        )

        cases.forEach { (error, expectedStatus) ->
            DefaultWebFluxErrorStrategy.toServerResponse(request, error)
                .test()
                .consumeNextWith { response ->
                    response.statusCode().assert().isEqualTo(expectedStatus)
                    response.headers().getFirst(ERROR_CODE).assert().isEqualTo(error.errorInfo.errorCode)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `should write throwable to web exchange`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").build()
        )

        DefaultWebFluxErrorStrategy.writeToExchange(exchange, IllegalArgumentException("bad"))
            .test()
            .verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        exchange.response.headers.contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
        exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `should write an unclassified throwable as a safe internal server error`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").build()
        )

        DefaultWebFluxErrorStrategy.writeToExchange(
            exchange,
            NullPointerException("sensitive implementation detail"),
        ).test().verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.INTERNAL_SERVER_ERROR)
        exchange.response.bodyAsString.block()!!.assert()
            .contains(ErrorCodes.INTERNAL_SERVER_ERROR, "Unexpected server error")
            .doesNotContain("sensitive implementation detail")
    }

    @Test
    fun `should preserve error response status in functional server response`() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("/missing"))
            .build()

        DefaultWebFluxErrorStrategy.toServerResponse(
            request,
            ResponseStatusException(HttpStatus.NOT_FOUND),
        )
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.NOT_FOUND)
            }
            .verifyComplete()
    }

    @Test
    fun `should preserve error response status in web exchange`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/test").build()
        )

        DefaultWebFluxErrorStrategy.writeToExchange(
            exchange,
            ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        )
            .test()
            .verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `should keep binding errors in functional server response`() {
        WebTestClient.bindToRouterFunction(
            route(POST("/test")) {
                DefaultWebFluxErrorStrategy.toServerResponse(it, bindException())
            }
        ).build()
            .post()
            .uri("/test")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            .expectBody(String::class.java)
            .consumeWith {
                val body = it.responseBody!!
                body.assert().contains("\"bindingErrors\"")
                body.assert().contains("\"name\":\"file\"")
                body.assert().contains("\"msg\":\"error\"")
            }
    }

    @Test
    fun `should keep binding errors in web exchange response`() {
        val body = slot<Publisher<out DataBuffer>>()
        val response = mockResponse(body)
        val exchange = mockExchange(response)

        DefaultWebFluxErrorStrategy.writeToExchange(exchange, bindException())
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
        }
        response.headers.contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
        response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
        val bodyString = body.captured.bodyToString()
        bodyString.assert().contains("\"bindingErrors\"")
        bodyString.assert().contains("\"name\":\"file\"")
        bodyString.assert().contains("\"msg\":\"error\"")
    }

    @Test
    fun `should skip committed web exchange response`() {
        val response = mockk<ServerHttpResponse>(relaxed = true) {
            every { isCommitted } returns true
            every { headers } returns HttpHeaders()
        }
        val exchange = mockExchange(response)

        DefaultWebFluxErrorStrategy.writeToExchange(exchange, IllegalArgumentException("bad"))
            .test()
            .verifyComplete()

        verify(exactly = 0) {
            response.statusCode = any()
            response.writeWith(any())
        }
        response.headers.getFirst(ERROR_CODE).assert().isNull()
        response.headers.contentType.assert().isNull()
    }

    private fun bindException(): BindException {
        val bindingResult = BeanPropertyBindingResult(Any(), "objectName")
        bindingResult.addError(FieldError("objectName", "file", "error"))
        return BindException(bindingResult)
    }

    private fun mockResponse(body: CapturingSlot<Publisher<out DataBuffer>>): ServerHttpResponse {
        return mockk {
            every { isCommitted } returns false
            every { setStatusCode(any()) } returns true
            every { headers } returns HttpHeaders()
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(capture(body)) } returns Mono.empty()
        }
    }

    private fun mockExchange(response: ServerHttpResponse): ServerWebExchange {
        val exchange = mockk<ServerWebExchange> {
            every { getResponse() } returns response
            every { getRequest() } returns MockServerHttpRequest.post("/test").build()
        }
        return exchange
    }

    private fun Publisher<out DataBuffer>.bodyToString(): String {
        val builder = StringBuilder()
        Flux.from(this)
            .test()
            .consumeNextWith {
                val bytes = ByteArray(it.readableByteCount())
                it.read(bytes)
                builder.append(String(bytes, StandardCharsets.UTF_8))
            }
            .verifyComplete()
        return builder.toString()
    }
}

private class RegisteredWebFluxFailure : RuntimeException()
