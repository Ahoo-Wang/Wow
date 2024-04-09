package me.ahoo.wow.webflux.route.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantIdOrDefault
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class LoadEventStreamHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = LoadEventStreamHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            eventStore = eventStore,
        )
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.ID_KEY) } returns GlobalIdGenerator.generateAsString()
            every { pathVariable(RoutePaths.HEAD_VERSION_KEY) } returns "0"
            every { pathVariable(RoutePaths.TAIL_VERSION_KEY) } returns Int.MAX_VALUE.toString()
            every { getTenantIdOrDefault(aggregateMetadata = MOCK_AGGREGATE_METADATA) } returns GlobalIdGenerator.generateAsString()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
