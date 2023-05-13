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
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.SimpleEventStreamExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.LocalSendMessageBus
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.opentelemetry.messaging.Tracing.setParentContext
import me.ahoo.wow.opentelemetry.messaging.TracingLocalCommandBus.Companion.sendExchangeWithTracing
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TracingMessageBus<B : MessageBus> : MessageBus, Decorator<B>

class TracingLocalCommandBus(override val delegate: CommandBus) :
    TracingMessageBus<CommandBus>,
    CommandBus {
    private val localSendMessageBus: LocalSendMessageBus<CommandMessage<*>, ServerCommandExchange<out Any>>

    init {
        require(delegate is LocalSendMessageBus<*, *>) {
            "delegate must be LocalSendMessageBus."
        }
        @Suppress("UNCHECKED_CAST")
        localSendMessageBus = delegate as LocalSendMessageBus<CommandMessage<*>, ServerCommandExchange<out Any>>
    }

    companion object {
        fun <E : MessageExchange<*>> Instrumenter<E, Unit>.sendExchangeWithTracing(
            exchange: E,
            send: (E) -> Mono<Void>
        ): Mono<Void> {
            val parentContext = Context.current()
            if (!shouldStart(parentContext, exchange)) {
                exchange.setParentContext(Context.current())
                return send(exchange)
            }
            val currentContext = start(parentContext, exchange)
            currentContext.makeCurrent().use {
                exchange.setParentContext(currentContext)
                return send(exchange)
                    .doOnError { end(currentContext, exchange, null, it) }
                    .doOnSuccess { end(currentContext, exchange, null, null) }
            }
        }
    }

    override fun send(message: CommandMessage<*>): Mono<Void> {
        val exchange = SimpleServerCommandExchange(message)
        return LocalCommandBusInstrumenter.INSTRUMENTER
            .sendExchangeWithTracing(exchange) { localSendMessageBus.sendExchange(it) }
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        return delegate.receive(namedAggregates)
    }
}

class TracingDistributedCommandBus(override val delegate: CommandBus) :
    TracingMessageBus<CommandBus>,
    CommandBus,
    Decorator<CommandBus> {
    override fun send(message: CommandMessage<*>): Mono<Void> {
        return delegate.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        return delegate.receive(namedAggregates)
            .map {
                it.setParentContext(Context.current())
            }
    }
}

class TracingLocalEventBus(override val delegate: DomainEventBus) :
    TracingMessageBus<DomainEventBus>,
    DomainEventBus,
    Decorator<DomainEventBus> {
    private val localSendMessageBus: LocalSendMessageBus<DomainEventStream, EventStreamExchange>

    init {
        require(delegate is LocalSendMessageBus<*, *>) {
            "delegate must be LocalSendMessageBus."
        }
        @Suppress("UNCHECKED_CAST")
        localSendMessageBus = delegate as LocalSendMessageBus<DomainEventStream, EventStreamExchange>
    }

    override fun send(message: DomainEventStream): Mono<Void> {
        val exchange = SimpleEventStreamExchange(message)
        return LocalEventBusInstrumenter.INSTRUMENTER
            .sendExchangeWithTracing(exchange) { localSendMessageBus.sendExchange(it) }
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return delegate.receive(namedAggregates)
    }
}

class TracingDistributedEventBus(override val delegate: DomainEventBus) :
    TracingMessageBus<DomainEventBus>,
    DomainEventBus,
    Decorator<DomainEventBus> {
    override fun send(message: DomainEventStream): Mono<Void> {
        return delegate.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return delegate.receive(namedAggregates)
            .map {
                it.setParentContext(Context.current())
            }
    }
}
