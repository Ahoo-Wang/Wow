package me.ahoo.wow.webflux.route.id

import me.ahoo.wow.openapi.id.GenerateGlobalIdRouteSpec
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class GlobalIdHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GlobalIdHandlerFunctionFactory().create(GenerateGlobalIdRouteSpec)
        val request = MockServerRequest.builder().build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
