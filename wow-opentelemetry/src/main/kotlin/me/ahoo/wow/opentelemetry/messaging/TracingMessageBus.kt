/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.context.Context
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.SimpleEventStreamExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.opentelemetry.messaging.Tracing.setParentContext
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TracingMessageBus<B : MessageBus<*, *>> : Decorator<B>

class TracingLocalCommandBus(override val delegate: LocalCommandBus) :
    TracingMessageBus<LocalCommandBus>,
    LocalCommandBus {
    override fun send(message: CommandMessage<*>): Mono<Void> {
        val exchange = SimpleServerCommandExchange(message)
        return sendExchange(exchange)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return delegate.receive(namedAggregates)
    }

    override fun sendExchange(exchange: ServerCommandExchange<*>): Mono<Void> {
        val source = delegate.sendExchange(exchange)
        val parentContext = Context.current()
        return MonoLocalBusTrace(
            parentContext = parentContext,
            instrumenter = LocalCommandBusInstrumenter.INSTRUMENTER,
            exchange = exchange,
            source = source,
        )
    }

    override fun close() {
        delegate.close()
    }
}

class TracingDistributedCommandBus(override val delegate: DistributedCommandBus) :
    TracingMessageBus<DistributedCommandBus>,
    DistributedCommandBus,
    Decorator<DistributedCommandBus> {
    override fun send(message: CommandMessage<*>): Mono<Void> {
        return delegate.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return delegate.receive(namedAggregates)
            .map {
                it.setParentContext(Context.current())
            }
    }

    override fun close() {
        delegate.close()
    }
}

class TracingLocalEventBus(override val delegate: LocalDomainEventBus) :
    TracingMessageBus<LocalDomainEventBus>,
    LocalDomainEventBus,
    Decorator<LocalDomainEventBus> {

    override fun send(message: DomainEventStream): Mono<Void> {
        val exchange = SimpleEventStreamExchange(message)
        return sendExchange(exchange)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return delegate.receive(namedAggregates)
    }

    override fun sendExchange(exchange: EventStreamExchange): Mono<Void> {
        val source = delegate.sendExchange(exchange)
        val parentContext = Context.current()
        return MonoLocalBusTrace(
            parentContext = parentContext,
            instrumenter = LocalEventBusInstrumenter.INSTRUMENTER,
            exchange = exchange,
            source = source,
        )
    }

    override fun close() {
        delegate.close()
    }
}

class TracingDistributedEventBus(override val delegate: DistributedDomainEventBus) :
    TracingMessageBus<DistributedDomainEventBus>,
    DistributedDomainEventBus,
    Decorator<DistributedDomainEventBus> {
    override fun send(message: DomainEventStream): Mono<Void> {
        return delegate.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return delegate.receive(namedAggregates)
            .map {
                it.setParentContext(Context.current())
            }
    }

    override fun close() {
        delegate.close()
    }
}
