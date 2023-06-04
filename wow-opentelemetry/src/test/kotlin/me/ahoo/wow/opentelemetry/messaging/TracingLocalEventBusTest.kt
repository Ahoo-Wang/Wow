package me.ahoo.wow.opentelemetry.messaging

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.opentelemetry.ExchangeTraceMonoTest
import me.ahoo.wow.opentelemetry.messaging.Tracing.tracing
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class TracingLocalEventBusTest {

    @Test
    fun send() {
        val event = mockk<DomainEvent<*>> {
            every { name } returns "test"
        }
        val eventStream = mockk<DomainEventStream> {
            every { id } returns GlobalIdGenerator.generateAsString()
            every { requestId } returns GlobalIdGenerator.generateAsString()
            every { header } returns DefaultHeader.empty()
            every { aggregateName } returns ExchangeTraceMonoTest.TEST_NAMED_AGGREGATE.aggregateName
            every { aggregateId } returns ExchangeTraceMonoTest.TEST_NAMED_AGGREGATE.asAggregateId()
            every { iterator() } returns listOf(event).iterator()
        }
        InMemoryDomainEventBus().tracing().use {
            it.send(eventStream)
                .test()
                .verifyComplete()
        }
    }
}
