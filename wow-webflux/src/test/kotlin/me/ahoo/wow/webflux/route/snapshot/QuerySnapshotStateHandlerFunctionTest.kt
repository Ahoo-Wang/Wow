package me.ahoo.wow.webflux.route.snapshot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.snapshot.QuerySnapshotStateRouteSpec
import me.ahoo.wow.query.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class QuerySnapshotStateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = QuerySnapshotStateHandlerFunctionFactory(
            NoOpSnapshotQueryServiceFactory,
            exceptionHandler = DefaultExceptionHandler,
        ).create(QuerySnapshotStateRouteSpec(MOCK_AGGREGATE_METADATA, MOCK_AGGREGATE_METADATA))
        val request = mockk<ServerRequest> {
            every { getTenantId(aggregateMetadata = MOCK_AGGREGATE_METADATA) } returns GlobalIdGenerator.generateAsString()
            every { bodyToMono(Query::class.java) } returns Query(Condition.ALL).toMono()
        }

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
