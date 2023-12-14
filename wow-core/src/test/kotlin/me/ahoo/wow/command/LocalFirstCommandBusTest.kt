package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import me.ahoo.wow.tck.command.CommandBusSpec
import reactor.core.publisher.Sinks

class LocalFirstCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus {
        return LocalFirstCommandBus(distributedBus = MockDistributedCommandBus())
    }
}

class MockDistributedCommandBus(
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<CommandMessage<*>> = {
        Sinks.many().multicast().onBackpressureBuffer()
    }
) : DistributedCommandBus, InMemoryMessageBus<CommandMessage<*>, ServerCommandExchange<*>>() {
    override fun CommandMessage<*>.createExchange(): ServerCommandExchange<*> {
        return SimpleServerCommandExchange(this)
    }
}
