package me.ahoo.wow.opentelemetry.messaging

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.opentelemetry.MonoTraceTest
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
            every { aggregateName } returns MonoTraceTest.TEST_NAMED_AGGREGATE.aggregateName
            every { aggregateId } returns MonoTraceTest.TEST_NAMED_AGGREGATE.asAggregateId()
            every { iterator() } returns listOf(event).iterator()
        }
        InMemoryDomainEventBus().tracing().use {
            it.send(eventStream)
                .test()
                .verifyComplete()
        }

    }
}