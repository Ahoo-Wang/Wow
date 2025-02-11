package me.ahoo.wow.webflux.route.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpec
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
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
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.ID_KEY) } returns GlobalIdGenerator.generateAsString()
            every { pathVariable(RoutePaths.HEAD_VERSION_KEY) } returns "0"
            every { pathVariable(RoutePaths.TAIL_VERSION_KEY) } returns Int.MAX_VALUE.toString()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns null
            every { headers().firstHeader(CommandRequestHeaders.TENANT_ID) } returns null
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
