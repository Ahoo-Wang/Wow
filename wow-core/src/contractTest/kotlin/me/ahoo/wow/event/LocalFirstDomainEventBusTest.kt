package me.ahoo.wow.event

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.tck.event.DomainEventBusSpec
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.EmitResult
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicBoolean

class LocalFirstDomainEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus {
        return LocalFirstDomainEventBus(
            MockDistributedDomainEventBus()
        )
    }

    @Test
    fun `should close local and distributed buses`() {
        val distributedBus = RecordingDistributedDomainEventBus()
        val localBus = RecordingLocalDomainEventBus()
        val messageBus = LocalFirstDomainEventBus(
            distributedBus = distributedBus,
            localBus = localBus,
        )

        messageBus.close()

        localBus.closed.get().assert().isTrue()
        distributedBus.closed.get().assert().isTrue()
    }

    @Test
    fun `should send message when no subscribers`() {
        val messageBus = LocalFirstDomainEventBus(
            MockDistributedDomainEventBus(),
            localBus = InMemoryDomainEventBus(sinkSupplier = {
                Sinks.many().multicast().directBestEffort()
            })
        )
        val message = createMessage()
        messageBus.send(message)
            .test()
            .verifyComplete()
    }

    @Test
    fun `should send message when error`() {
        val messageBus = LocalFirstDomainEventBus(
            MockDistributedDomainEventBus(),
            localBus = InMemoryDomainEventBus(sinkSupplier = {
                mockk<Sinks.Many<DomainEventStream>> {
                    every { currentSubscriberCount() } returns 0
                    every { tryEmitNext(any()) } returns EmitResult.FAIL_OVERFLOW
                }
            })
        )
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

private class RecordingLocalDomainEventBus : LocalDomainEventBus {
    val closed = AtomicBoolean()

    override fun send(message: DomainEventStream): Mono<Void> = Mono.empty()

    override fun receive(subscription: MessageSubscription): Flux<EventStreamExchange> = Flux.empty()

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = 0

    override fun close() {
        closed.set(true)
    }
}

private class RecordingDistributedDomainEventBus : DistributedDomainEventBus {
    val closed = AtomicBoolean()

    override fun send(message: DomainEventStream): Mono<Void> = Mono.empty()

    override fun receive(subscription: MessageSubscription): Flux<EventStreamExchange> = Flux.empty()

    override fun close() {
        closed.set(true)
    }
}
