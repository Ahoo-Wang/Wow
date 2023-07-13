package me.ahoo.wow.webflux.exception

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import reactor.kotlin.test.test

class DefaultExceptionHandlerTest {

    @Test
    fun handle() {
        DefaultExceptionHandler.handle(IllegalArgumentException("error"))
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.BAD_REQUEST))
            }
            .verifyComplete()
    }
}
