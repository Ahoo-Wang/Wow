package me.ahoo.wow.webflux.route

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.AggregateIdScanner.Companion.FIRST_CURSOR_ID
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.appender.RoutePaths
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class RegenerateStateEventHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = RegenerateStateEventFunction(
            MOCK_AGGREGATE_METADATA,
            eventStore,
            StateEventCompensator(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                eventStore = eventStore,
                stateEventBus = InMemoryStateEventBus(),
            ),
            DefaultExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.BATCH_CURSOR_ID) } returns FIRST_CURSOR_ID
            every { pathVariable(RoutePaths.BATCH_LIMIT) } returns Int.MAX_VALUE.toString()
            every { bodyToMono(CompensationConfig::class.java) } returns CompensationConfig.EMPTY.toMono()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
