package me.ahoo.wow.webflux.exception

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.net.URI

class DefaultExceptionHandlerTest {

    @Test
    fun handle() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .build()
        DefaultRequestExceptionHandler.handle(request, IllegalArgumentException("error"))
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.BAD_REQUEST))
            }
            .verifyComplete()
    }
}
