package me.ahoo.wow.command.wait

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.event.DomainEvent
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class EventHandledNotifierFilterTest {

    @Test
    fun filter() {
        val notifierFilter = EventHandledNotifierFilter(LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar))
        val domainEvent = mockk<DomainEvent<Any>>()
        every { domainEvent.header } returns DefaultHeader.empty()
        every { domainEvent.commandId } returns GlobalIdGenerator.generateAsString()
        every { domainEvent.isLast } returns true
        val exchange = SimpleDomainEventExchange(domainEvent, mockk())
        val chain = FilterChainBuilder<DomainEventExchange<Any>>().build()
        notifierFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }
}
