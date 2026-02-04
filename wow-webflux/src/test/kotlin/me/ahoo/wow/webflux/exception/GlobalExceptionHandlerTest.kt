package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI

class GlobalExceptionHandlerTest {

    @Test
    fun getOrder() {
        GlobalExceptionHandler.order.assert().isEqualTo(-2)
    }

    @Test
    fun handle() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { isCommitted } returns false
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(any()) } returns Mono.empty()
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }

        GlobalExceptionHandler.handle(exchange, IllegalArgumentException("error"))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            response.headers.set(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    @Test
    fun handleReadOnlyHeaders() {
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

        GlobalExceptionHandler.handle(exchange, IllegalArgumentException("error"))
            .test()
            .verifyComplete()
    }

    @Test
    fun handleIfBindingError() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
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
        GlobalExceptionHandler.handle(exchange, BindException(bindingResult))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            response.headers.set(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }
}
