package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
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
                it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
            }
            .verifyComplete()
    }
}
