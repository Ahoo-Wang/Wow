package me.ahoo.wow.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.tck.event.DomainEventBusSpec
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

class LocalFirstDomainEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus {
        return LocalFirstDomainEventBus(
            MockDistributedDomainEventBus(),
            localBus = InMemoryDomainEventBus(sinkSupplier = {
                Sinks.many().multicast().directBestEffort()
            })
        )
    }

    @Test
    fun sendMessageWhenNoSubscribers() {
        val messageBus = createMessageBus()
        val message = createMessage()
        messageBus.send(message)
            .test()
            .verifyComplete()
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
