package me.ahoo.wow.webflux.route.query

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.state.IdList
import me.ahoo.wow.webflux.route.state.IdsQueryAggregateHandlerFunction
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class IdsQueryAggregateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = IdsQueryAggregateHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotRepository = NoOpSnapshotRepository,
                eventStore = InMemoryEventStore(),
            ),
            exceptionHandler = DefaultExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { getTenantIdOrDefault(aggregateMetadata = MOCK_AGGREGATE_METADATA) } returns GlobalIdGenerator.generateAsString()
            every { bodyToMono(IdList) } returns setOf("id").toMono()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
