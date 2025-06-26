package me.ahoo.wow.webflux.route.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.global.GetWowMetadataRouteSpec
import me.ahoo.wow.webflux.route.global.GetWowMetadataHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class GetWowMetadataHandlerFunctionTest {
    @Test
    fun handle() {
        val handlerFunction = GetWowMetadataHandlerFunctionFactory().create(
            GetWowMetadataRouteSpec(
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
