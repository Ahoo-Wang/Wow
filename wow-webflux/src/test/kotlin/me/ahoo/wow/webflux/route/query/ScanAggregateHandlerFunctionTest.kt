package me.ahoo.wow.webflux.route.query

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.state.ScanAggregateHandlerFunction
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class ScanAggregateHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = ScanAggregateHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotRepository = NoOpSnapshotRepository,
                eventStore = eventStore,
            ),
            snapshotRepository = NoOpSnapshotRepository,
            exceptionHandler = DefaultRequestExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.BATCH_AFTER_ID) } returns AggregateIdScanner.FIRST_ID
            every { pathVariable(RoutePaths.BATCH_LIMIT) } returns Int.MAX_VALUE.toString()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
