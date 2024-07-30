package me.ahoo.wow.webflux.route.bi

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.openapi.bi.GenerateBIScriptRouteSpec
import me.ahoo.wow.openapi.bi.GenerateBIScriptRouteSpecFactory.Companion.BI_HEADER_TYPE_HEADER
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class GenerateBIScriptHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory().create(GenerateBIScriptRouteSpec)
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(BI_HEADER_TYPE_HEADER) } returns "STRING"
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }

    @Test
    fun handleEmpty() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory().create(GenerateBIScriptRouteSpec)
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(BI_HEADER_TYPE_HEADER) } returns null
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
