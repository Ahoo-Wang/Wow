package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec
import reactor.core.publisher.Sinks

class LocalFirstStateEventBusTest : StateEventBusSpec() {
    override fun createMessageBus(): StateEventBus {
        return LocalFirstStateEventBus(MockDistributedStateEventBus())
    }
}

class MockDistributedStateEventBus(
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<StateEvent<*>> = {
        Sinks.many().multicast().onBackpressureBuffer()
    }
) : DistributedStateEventBus, InMemoryMessageBus<StateEvent<*>, StateEventExchange<*>>() {

    override fun StateEvent<*>.createExchange(): StateEventExchange<*> {
        return SimpleStateEventExchange(this)
    }
}
