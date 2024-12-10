package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.command.CommandHeaders
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.context.MessageSourceResolvable
import org.springframework.core.MethodParameter
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.validation.method.MethodValidationResult
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI
import java.util.function.BiFunction

class GlobalExceptionHandlerTest {

    @Test
    fun getOrder() {
        assertThat(GlobalExceptionHandler.order, equalTo(-2))
    }

    @Test
    fun handle() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
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
            response.headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    @Test
    fun handleIfBindingError() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
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
            response.headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    @Test
    fun handleIfHandlerMethodValidationException() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(any()) } returns Mono.empty()
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }

        val method = GlobalExceptionHandlerTest::class.java.declaredMethods.first {
            it.name == "mockMethod"
        }

        val methodParameter = MethodParameter(method, 0)
        val error = mockk<MessageSourceResolvable> {
            every { defaultMessage } returns "error"
        }

        val methodValidationResult = mockk<MethodValidationResult> {
            every { parameterValidationResults } returns listOf(
                ParameterValidationResult(
                    methodParameter,
                    "file",
                    listOf(error),
                    null, null, null,
                    BiFunction<MessageSourceResolvable, Class<*>, Any> { _, _ -> IllegalArgumentException("No source object of the given type") }
                )
            )
            every { isForReturnValue } returns false
        }
        GlobalExceptionHandler.handle(exchange, HandlerMethodValidationException(methodValidationResult))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            response.headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    @Test
    fun handleNoResourceFoundException() {
        val request = mockk<ServerHttpRequest> {
            every { method } returns HttpMethod.GET
            every { uri } returns URI.create("http://localhost:8080")
        }

        val response = mockk<ServerHttpResponse> {
            every { setStatusCode(any()) } returns true
            every { headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.NOT_FOUND) } returns Unit
            every { headers.contentType = MediaType.APPLICATION_JSON } returns Unit
            every { bufferFactory() } returns DefaultDataBufferFactory()
            every { writeWith(any()) } returns Mono.empty()
        }

        val exchange = mockk<ServerWebExchange> {
            every { getRequest() } returns request
            every { getResponse() } returns response
        }

        GlobalExceptionHandler.handle(exchange, NoResourceFoundException("error"))
            .test()
            .verifyComplete()

        verify {
            response.setStatusCode(HttpStatus.NOT_FOUND)
            response.headers.set(CommandHeaders.WOW_ERROR_CODE, ErrorCodes.NOT_FOUND)
            response.headers.contentType = MediaType.APPLICATION_JSON
            response.writeWith(any())
        }
    }

    fun mockMethod(@Suppress("UNUSED_PARAMETER") parameter: String) = Unit
}
