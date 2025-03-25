package me.ahoo.wow.webflux.route.bi

import me.ahoo.wow.bi.MessageHeaderSqlType
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.global.GenerateBIScriptRouteSpec
import me.ahoo.wow.openapi.global.GenerateBIScriptRouteSpecFactory.Companion.BI_HEADER_TYPE_HEADER
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
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
            .header(BI_HEADER_TYPE_HEADER, MessageHeaderSqlType.STRING.name)
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
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
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
