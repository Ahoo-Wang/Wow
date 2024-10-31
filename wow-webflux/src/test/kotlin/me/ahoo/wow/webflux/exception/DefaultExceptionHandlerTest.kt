package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test
import java.net.URI

class DefaultExceptionHandlerTest {

    @Test
    fun handle() {
        val serverRequest = mockk<ServerRequest> {
            every { method() } returns HttpMethod.GET
            every { uri() } returns URI.create("http://localhost")
        }
        DefaultRequestExceptionHandler.handle(serverRequest, IllegalArgumentException("error"))
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.BAD_REQUEST))
            }
            .verifyComplete()
    }
}
