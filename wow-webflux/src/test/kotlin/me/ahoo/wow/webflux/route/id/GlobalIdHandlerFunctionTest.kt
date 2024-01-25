package me.ahoo.wow.webflux.route.id

import io.mockk.mockk
import me.ahoo.wow.openapi.id.GlobalIdRouteSpec
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class GlobalIdHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GlobalIdHandlerFunctionFactory().create(GlobalIdRouteSpec)
        val request = mockk<ServerRequest>()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
