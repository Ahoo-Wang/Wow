package me.ahoo.wow.webflux.route.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.openapi.event.ListQueryEventStreamRouteSpec
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
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
                NoOpEventStreamQueryServiceFactory,
                DefaultRequestExceptionHandler
            )
                .create(
                    ListQueryEventStreamRouteSpec(
                        MOCK_AGGREGATE_METADATA,
                        aggregateMetadata = MOCK_AGGREGATE_METADATA,
                        true
                    )
                )
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.TENANT_ID] } returns null
            every { headers().firstHeader(CommandHeaders.TENANT_ID) } returns null
            every { bodyToMono(ListQuery::class.java) } returns ListQuery(Condition.ALL).toMono()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
