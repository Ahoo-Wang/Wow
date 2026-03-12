package me.ahoo.wow.webflux.route.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.global.GenerateBIScriptRouteSpec
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class GenerateBIScriptHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory().create(
            GenerateBIScriptRouteSpec(
                OpenAPIComponentContext.default()
            )
        )
        val request = MockServerRequest.builder()
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun handleEmpty() {
        val handlerFunction =
            GenerateBIScriptHandlerFunctionFactory().create(
                GenerateBIScriptRouteSpec(OpenAPIComponentContext.default())
            )
        val request = MockServerRequest.builder().build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
