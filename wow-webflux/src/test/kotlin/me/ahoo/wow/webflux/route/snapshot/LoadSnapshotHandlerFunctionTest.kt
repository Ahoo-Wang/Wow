package me.ahoo.wow.webflux.route.snapshot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class LoadSnapshotHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = LoadSnapshotHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            snapshotRepository = InMemorySnapshotRepository(),
            exceptionHandler = DefaultExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.ID_KEY) } returns GlobalIdGenerator.generateAsString()
            every { getTenantId(aggregateMetadata = MOCK_AGGREGATE_METADATA) } returns GlobalIdGenerator.generateAsString()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.NOT_FOUND))
            }.verifyComplete()
    }
}