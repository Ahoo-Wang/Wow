package me.ahoo.wow.command.wait

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.saga.stateless.DefaultCommandStream
import me.ahoo.wow.saga.stateless.setCommandStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SagaHandledNotifierFilterTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun filter() {
        val notifierFilter = SagaHandledNotifierFilter(LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar))
        val domainEvent = MockAggregateCreated(generateGlobalId()).toDomainEvent(
            MOCK_AGGREGATE_METADATA.aggregateId(),
            generateGlobalId()
        ) as DomainEvent<Any>
        WaitingForStage.sagaHandled(domainEvent.contextName).propagate("", domainEvent.header)
        val exchange = SimpleDomainEventExchange(domainEvent)
        val commandMessage = MockCreateAggregate(generateGlobalId(), generateGlobalId()).toCommandMessage()
        val commandStream = DefaultCommandStream(generateGlobalId(), listOf(commandMessage))
        exchange.setCommandStream(commandStream)
        val chain = FilterChainBuilder<DomainEventExchange<Any>>().build()
        notifierFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }
}
