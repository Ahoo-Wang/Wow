package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.tck.command.CommandBusSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

class LocalFirstCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus {
        return LocalFirstCommandBus(distributedBus = MockDistributedCommandBus())
    }
}

class MockDistributedCommandBus : DistributedCommandBus {
    private val sink: Sinks.Many<ServerCommandExchange<*>> = Sinks.many().unicast().onBackpressureBuffer()

    override fun send(message: CommandMessage<*>): Mono<Void> {
        val exchange = SimpleServerCommandExchange(message)
        return Mono.fromRunnable {
            @Suppress("UNCHECKED_CAST")
            sink.emitNext(
                exchange as ServerCommandExchange<Any>,
                Sinks.EmitFailureHandler.busyLooping(BUSY_LOOPING_DURATION),
            )
        }
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return sink.asFlux().filter {
            namedAggregates.any { namedAggregate ->
                namedAggregate.isSameAggregateName(it.message)
            }
        }
    }
}
