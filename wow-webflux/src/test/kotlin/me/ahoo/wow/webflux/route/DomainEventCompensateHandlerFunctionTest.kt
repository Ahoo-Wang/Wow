package me.ahoo.wow.webflux.route

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.appender.RoutePaths
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import me.ahoo.wow.webflux.route.compensation.DomainEventCompensateHandlerFunction
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class DomainEventCompensateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = DomainEventCompensateHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            eventCompensator = DomainEventCompensator(
                eventStore = InMemoryEventStore(),
                eventBus = InMemoryDomainEventBus(),
            ),
            exceptionHandler = DefaultExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.ID_KEY) } returns GlobalIdGenerator.generateAsString()
            every { pathVariable(RoutePaths.COMPENSATE_HEAD_VERSION_KEY) } returns "0"
            every { pathVariable(RoutePaths.COMPENSATE_TAIL_VERSION_KEY) } returns Int.MAX_VALUE.toString()
            every { getTenantId(aggregateMetadata = MOCK_AGGREGATE_METADATA) } returns GlobalIdGenerator.generateAsString()
            every { bodyToMono(CompensationConfig::class.java) } returns CompensationConfig.EMPTY.toMono()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
