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

package me.ahoo.wow.saga.stateless

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * A stateless saga function that processes domain events and generates command streams.
 * This function wraps a delegate message function and extends its behavior to handle command execution
 * and collection into a [CommandStream] for stateless saga processing.
 *
 * @property delegate The underlying message function that handles the domain event processing.
 * @property commandGateway The gateway used to send commands.
 * @property commandMessageFactory The factory for creating command messages.
 */
class StatelessSagaFunction(
    override val delegate: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    private val commandGateway: CommandGateway,
    private val commandMessageFactory: CommandMessageFactory
) : MessageFunction<Any, DomainEventExchange<*>, Mono<CommandStream>>,
    Decorator<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
    override val contextName: String = delegate.contextName
    override val name: String = delegate.name
    override val processor: Any = delegate.processor
    override val supportedType: Class<*> = delegate.supportedType
    override val supportedTopics: Set<NamedAggregate> = delegate.supportedTopics
    override val functionKind: FunctionKind = delegate.functionKind

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = delegate.getAnnotation(annotationClass)

    override fun invoke(exchange: DomainEventExchange<*>): Mono<CommandStream> =
        delegate
            .invoke(exchange)
            .flatMapMany {
                toCommandFlux(exchange.message, it)
            }.concatMap {
                commandGateway.send(it).thenReturn(it)
            }.collectList()
            .map {
                val commandStream = DefaultCommandStream(exchange.message.id, it)
                exchange.setCommandStream(commandStream)
                commandStream
            }

    private fun toCommandFlux(
        domainEvent: DomainEvent<*>,
        handleResult: Any
    ): Publisher<CommandMessage<*>> {
        if (handleResult !is Iterable<*>) {
            return toCommand(domainEvent = domainEvent, singleResult = handleResult)
        }
        return Flux
            .fromIterable(handleResult as Iterable<Any>)
            .index()
            .flatMap {
                toCommand(domainEvent = domainEvent, singleResult = it.t2!!, index = it.t1.toInt())
            }
    }

    private fun toCommand(
        domainEvent: DomainEvent<*>,
        singleResult: Any,
        index: Int = 0
    ): Mono<CommandMessage<*>> {
        if (singleResult is CommandMessage<*>) {
            singleResult.header.propagate(domainEvent)
            return singleResult.toMono()
        }
        val commandBuilder = singleResult as? CommandBuilder ?: singleResult.commandBuilder()
        commandBuilder
            .requestIdIfAbsent("${domainEvent.id}-$index")
            .tenantIdIfAbsent(domainEvent.aggregateId.tenantId)
            .spaceIdIfAbsent(domainEvent.spaceId)
            .upstream(domainEvent)
            .header {
                it.propagate(domainEvent)
            }
        @Suppress("UNCHECKED_CAST")
        return commandMessageFactory.create<Any>(commandBuilder) as Mono<CommandMessage<*>>
    }

    override fun toString(): String = "StatelessSagaFunction(actual=$delegate)"
}
