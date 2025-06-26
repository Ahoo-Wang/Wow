package me.ahoo.wow.webflux.route.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.aggregate.event.LoadEventStreamRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
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
                    aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                    componentContext = OpenAPIComponentContext.default()
                )
            )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(BatchComponent.PathVariable.HEAD_VERSION, "0")
            .pathVariable(BatchComponent.PathVariable.TAIL_VERSION, Int.MAX_VALUE.toString())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
