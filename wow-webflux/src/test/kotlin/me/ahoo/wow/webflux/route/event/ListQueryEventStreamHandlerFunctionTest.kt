package me.ahoo.wow.webflux.route.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.event.ListQueryEventStreamRouteSpec
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class ListQueryEventStreamHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction =
            ListQueryEventStreamHandlerFunctionFactory(
                eventStreamQueryHandler = MockQueryHandler.queryHandler,
                exceptionHandler = DefaultRequestExceptionHandler
            )
                .create(
                    ListQueryEventStreamRouteSpec(
                        MOCK_AGGREGATE_METADATA,
                        aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                        true
                    )
                )
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.TENANT_ID] } returns null
            every { headers().firstHeader(CommandRequestHeaders.TENANT_ID) } returns null
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { bodyToMono(ListQuery::class.java) } returns ListQuery(Condition.ALL).toMono()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
