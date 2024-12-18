package me.ahoo.wow.webflux.route.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.openapi.event.ListQueryEventStreamRouteSpec
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.filter.DefaultEventStreamQueryHandler
import me.ahoo.wow.query.event.filter.EventStreamQueryContext
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.event.filter.TailEventStreamQueryFilter
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
    private val tailSnapshotQueryFilter = TailEventStreamQueryFilter(NoOpEventStreamQueryServiceFactory)
    private val queryFilterChain = FilterChainBuilder<EventStreamQueryContext<*, *, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(EventStreamQueryHandler::class)
        .build()
    private val queryHandler = DefaultEventStreamQueryHandler(
        queryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun handle() {
        val handlerFunction =
            ListQueryEventStreamHandlerFunctionFactory(
                queryHandler,
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
