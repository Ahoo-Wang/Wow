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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.rest.WowHeaders
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI

class DefaultGlobalExceptionHandlerTest {

    @Test
    fun `should get handler order`() {
        DefaultGlobalExceptionHandler().order.assert().isEqualTo(-2)
    }

    @Test
    fun `should handle exception and write error response`() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { isCommitted } returns false
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(any()) } returns Mono.empty()
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }

        DefaultGlobalExceptionHandler().handle(exchange, IllegalArgumentException("error"))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            response.headers.set(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    @Test
    fun `should handle exception when response is already committed`() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { isCommitted } returns true
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }

        DefaultGlobalExceptionHandler().handle(exchange, IllegalArgumentException("error"))
            .test()
            .verifyComplete()
    }

    @Test
    fun `should handle binding exception with field errors`() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { isCommitted } returns false
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(any()) } returns Mono.empty()
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }
        val bindingResult = mockk<BindingResult> {
            every { fieldErrors } returns listOf(FieldError("objectName", "file", "error"))
        }
        DefaultGlobalExceptionHandler().handle(exchange, BindException(bindingResult))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            response.headers.set(WowHeaders.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    private fun logged(throwable: Throwable): Pair<HttpStatus?, ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DefaultGlobalExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/assets/missing.js"))
        try {
            DefaultGlobalExceptionHandler().handle(exchange, throwable).test().verifyComplete()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        appender.list.assert().hasSize(1)
        return exchange.response.statusCode?.let { HttpStatus.resolve(it.value()) } to appender.list.single()
    }

    /** A request's own 4xx is one line; a stack trace is kept for a server fault (5xx, or a 400 IllegalState). */
    @Test
    fun `should log by the status the error was answered with`() {
        val (notFound, clientEvent) = logged(ResponseStatusException(HttpStatus.NOT_FOUND))
        notFound.assert().isEqualTo(HttpStatus.NOT_FOUND)
        clientEvent.level.assert().isEqualTo(Level.WARN)
        clientEvent.throwableProxy.assert().isNull()

        val (internal, serverEvent) = logged(RuntimeException("backend failed"))
        internal.assert().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        serverEvent.level.assert().isEqualTo(Level.ERROR)
        serverEvent.throwableProxy.assert().isNotNull()

        val (illegalState, stateEvent) = logged(IllegalStateException("unexpected state"))
        illegalState.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        stateEvent.level.assert().isEqualTo(Level.WARN)
        stateEvent.throwableProxy.assert().isNotNull()
    }
}
