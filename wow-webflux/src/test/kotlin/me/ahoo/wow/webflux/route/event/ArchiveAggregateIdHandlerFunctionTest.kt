package me.ahoo.wow.webflux.route.event

import io.mockk.mockk
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class ArchiveAggregateIdHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = ArchiveAggregateIdHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            eventStore = eventStore,
            exceptionHandler = DefaultExceptionHandler
        )
        val request = mockk<ServerRequest>()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
