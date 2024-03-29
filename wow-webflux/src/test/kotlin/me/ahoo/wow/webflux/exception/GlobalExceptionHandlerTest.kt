package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.command.CommandHeaders
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI

class GlobalExceptionHandlerTest {

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
}
