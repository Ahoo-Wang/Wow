package me.ahoo.wow.eventsourcing.state

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.MockNamedEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.command.getCommandAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregate.Companion.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class SendStateEventFilterTest {

    @Test
    fun filterIfEventStreamIsNull() {
        val stateEventBus = mockk<StateEventBus> {
            every { send(any()) } returns Mono.empty()
        }
        val stateEventFilter = SendStateEventFilter(stateEventBus)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns null
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }
        stateEventFilter.filter(exchange, next)
            .test()
            .verifyComplete()

        verify {
            stateEventBus.send(any()) wasNot Called
            next.filter(exchange)
        }
    }

    @Test
    fun filterIfCommandAggregateIsNull() {
        val stateEventBus = mockk<StateEventBus> {
            every { send(any()) } returns Mono.empty()
        }
        val stateEventFilter = SendStateEventFilter(stateEventBus)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns mockk()
            every { getCommandAggregate<Any, Any>() } returns null
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }
        stateEventFilter.filter(exchange, next)
            .test()
            .verifyComplete()

        verify {
            stateEventBus.send(any()) wasNot Called
            next.filter(exchange)
        }
    }

    @Test
    fun filterIfStateNotInitialized() {
        val stateEventBus = mockk<StateEventBus> {
            every { send(any()) } returns Mono.empty()
        }
        val stateEventFilter = SendStateEventFilter(stateEventBus)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns mockk()
            every { getCommandAggregate<Any, Any>() } returns mockk {
                every { state } returns mockk {
                    every { initialized } returns false
                }
            }
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }
        stateEventFilter.filter(exchange, next)
            .test()
            .verifyComplete()

        verify {
            stateEventBus.send(any()) wasNot Called
            next.filter(exchange)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun filter() {
        val stateEventBus = mockk<StateEventBus> {
            every { send(any()) } returns Mono.empty()
        }
        val stateEventFilter = SendStateEventFilter(stateEventBus)
        val eventStream = MockNamedEvent().toDomainEvent(
            aggregateId = GlobalIdGenerator.generateAsString(),
            tenantId = GlobalIdGenerator.generateAsString(),
            commandId = GlobalIdGenerator.generateAsString(),
            version = 1
        ).let {
            SimpleDomainEventStream(requestId = GlobalIdGenerator.generateAsString(), body = listOf(it))
        }
        val mockAggregate = MockStateAggregate(GlobalIdGenerator.generateAsString())
        val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(mockAggregate, 1)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns eventStream
            every { getCommandAggregate<Any, Any>() } returns mockk {
                every { state } returns stateAggregate as StateAggregate<Any>
            }
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }
        stateEventFilter.filter(exchange, next)
            .test()
            .verifyComplete()

        verify {
            stateEventBus.send(any())
            next.filter(exchange)
        }
    }
}
