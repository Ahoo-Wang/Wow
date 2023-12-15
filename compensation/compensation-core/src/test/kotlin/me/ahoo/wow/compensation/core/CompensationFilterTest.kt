package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class CompensationFilterTest {

    @Test
    fun filterSuccessAndExecutionIdIsNull() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = CompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
        }

        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns Mono.empty()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .verifyComplete()
    }

    @Test
    fun filterSuccessAndExecutionIdNotNull() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = CompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
                .with(COMPENSATION_ID, GlobalIdGenerator.generateAsString())
        }

        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns Mono.empty()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .verifyComplete()
    }

    @Test
    fun filterErrorAndEventFunctionIsNull() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = CompensationFilter(commandBus)
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.header } returns DefaultHeader.empty()
            every { getEventFunction() } returns null
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }

    @Test
    fun filterError() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = CompensationFilter(commandBus)
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".asNamedAggregate().asAggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
            every { getEventFunction() } returns eventFunction
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }

    @Test
    fun filterErrorExecutionIdNotNull() {
        val commandBus = InMemoryCommandBus()
        val compensationFilter = CompensationFilter(commandBus)
        val eventFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            every { functionKind } returns FunctionKind.EVENT
            every { contextName } returns "contextName"
            every { processorName } returns "processorName"
        }
        val exchange = mockk<DomainEventExchange<*>> {
            every { message.id } returns GlobalIdGenerator.generateAsString()
            every { message.aggregateId } returns "test.test".asNamedAggregate().asAggregateId()
            every { message.version } returns 1
            every { message.header } returns DefaultHeader.empty()
                .with(COMPENSATION_ID, GlobalIdGenerator.generateAsString())
            every { getEventFunction() } returns eventFunction
        }
        val error = IllegalStateException()
        val next: FilterChain<DomainEventExchange<*>> = mockk {
            every { filter(exchange) } returns error.toMono()
        }
        compensationFilter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()
    }
}
