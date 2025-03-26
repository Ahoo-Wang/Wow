package me.ahoo.wow.webflux.route.metadata

import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.global.GetWowMetadataRouteSpec
import me.ahoo.wow.webflux.route.global.GetWowMetadataHandlerFunctionFactory
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
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
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
