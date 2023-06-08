package me.ahoo.wow.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.tck.event.DomainEventBusSpec
import reactor.core.publisher.Sinks

class LocalFirstDomainEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus {
        return LocalFirstDomainEventBus(MockDistributedDomainEventBus())
    }
}

class MockDistributedDomainEventBus(
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<DomainEventStream> = {
        Sinks.many().multicast().onBackpressureBuffer()
    }
) : DistributedDomainEventBus, InMemoryMessageBus<DomainEventStream, EventStreamExchange>() {
    override fun DomainEventStream.createExchange(): EventStreamExchange {
        return SimpleEventStreamExchange(this)
    }
}
