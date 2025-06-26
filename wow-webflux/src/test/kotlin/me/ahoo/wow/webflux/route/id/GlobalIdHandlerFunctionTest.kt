package me.ahoo.wow.webflux.route.id

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.global.GenerateGlobalIdRouteSpec
import me.ahoo.wow.webflux.route.global.GlobalIdHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class GlobalIdHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GlobalIdHandlerFunctionFactory().create(
            GenerateGlobalIdRouteSpec(
                componentContext = OpenAPIComponentContext.default()
            )
        )
        val request = MockServerRequest.builder().build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
