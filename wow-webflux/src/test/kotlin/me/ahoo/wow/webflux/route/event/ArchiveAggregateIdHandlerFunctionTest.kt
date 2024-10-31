package me.ahoo.wow.webflux.route.event

import io.mockk.mockk
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class ArchiveAggregateIdHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = ArchiveAggregateIdHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            eventStore = eventStore,
            exceptionHandler = DefaultRequestExceptionHandler
        )
        val request = mockk<ServerRequest>()
        handlerFunction.handle(request)
            .test()
            .verifyComplete()
    }
}
