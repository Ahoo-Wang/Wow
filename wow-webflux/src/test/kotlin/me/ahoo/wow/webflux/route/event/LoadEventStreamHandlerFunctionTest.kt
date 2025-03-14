package me.ahoo.wow.webflux.route.event

import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpec
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class LoadEventStreamHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = LoadEventStreamHandlerFunctionFactory(
            eventStreamQueryHandler = MockQueryHandler.queryHandler,
            DefaultRequestExceptionHandler
        )
            .create(
                LoadEventStreamRouteSpec(
                    MOCK_AGGREGATE_METADATA,
                    aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
                )
            )

        val request = MockServerRequest.builder()
            .pathVariable(RoutePaths.ID_KEY, generateGlobalId())
            .pathVariable(RoutePaths.HEAD_VERSION_KEY, "0")
            .pathVariable(RoutePaths.TAIL_VERSION_KEY, Int.MAX_VALUE.toString())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
