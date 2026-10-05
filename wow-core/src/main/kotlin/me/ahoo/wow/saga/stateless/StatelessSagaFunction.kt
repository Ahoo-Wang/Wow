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
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.command.annotation.commandMetadata
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.identity.IdentityFact
import me.ahoo.wow.identity.IdentityHint
import me.ahoo.wow.identity.IdentityResolver
import me.ahoo.wow.identity.IdentitySource
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.propagation.MessagePropagators
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * A stateless saga function that processes domain events and generates command streams.
 * This function wraps a delegate message function and extends its behavior to handle command execution
 * and collection into a [CommandStream] for stateless saga processing.
 *
 * @param delegate The underlying message function that handles the domain event processing.
 * @param commandGateway The gateway used to send commands.
 * @param commandMessageFactory The factory for creating command messages.
 * @param messagePropagator Propagates the event's context into the commands this saga function sends, with this
 * function as their producer: a chain wait reaches only the commands of the saga function it waits for.
 */
class StatelessSagaFunction(
    override val delegate: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    private val commandGateway: CommandGateway,
    private val commandMessageFactory: CommandMessageFactory,
    private val messagePropagator: MessagePropagators = MessagePropagators.DEFAULT,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<CommandStream>>,
    Decorator<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
    override val contextName: String = delegate.contextName
    override val name: String = delegate.name
    override val processor: Any = delegate.processor
    override val supportedType: Class<*> = delegate.supportedType
    override val supportedTopics: Set<NamedAggregate> = delegate.supportedTopics
    override val functionKind: FunctionKind = delegate.functionKind

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = delegate.getAnnotation(annotationClass)

    @Suppress("UNCHECKED_CAST")
    override fun invoke(exchange: DomainEventExchange<*>): Mono<CommandStream> =
        Mono.defer { delegate.invoke(exchange) }
            .flatMapIterable { it as? Iterable<Any> ?: listOf(it) }
            .index()
            .concatMap { indexed ->
                toCommand(exchange.message, indexed.t2, indexed.t1.toInt())
                    .delayUntil { command ->
                        Mono.defer { commandGateway.send(command) }
                            .onErrorComplete(DuplicateRequestIdException::class.java)
                    }
            }
            .collectList()
            .map { DefaultCommandStream(exchange.message.id, it).also(exchange::setCommandStream) }

    private fun toCommand(
        domainEvent: DomainEvent<*>,
        singleResult: Any,
        index: Int = 0
    ): Mono<CommandMessage<*>> {
        if (singleResult is CommandMessage<*>) {
            val command = singleResult.withDerivedRequestId(domainEvent, index)
            messagePropagator.propagate(command.header, domainEvent, this)
            return command.toMono()
        }
        val commandBuilder = singleResult as? CommandBuilder ?: singleResult.commandBuilder()
        commandBuilder
            .requestIdIfAbsent(SagaCommandIds.requestId(domainEvent, index))
            .derivedAggregateIdIfAbsent(domainEvent, index)
            // What the saga sets wins over the event it reacts to; the command factory then puts the body first.
            .tenantId(
                IdentityResolver.resolve(
                    IdentityFact.TENANT_ID,
                    IdentityHint.of(IdentitySource.HEADER, commandBuilder.tenantId),
                    IdentityHint(IdentitySource.UPSTREAM, domainEvent.aggregateId.tenantId),
                )
            )
            // The command factory drops this space when the target aggregate is not spaced.
            .spaceId(
                IdentityResolver.resolve(
                    IdentityFact.SPACE_ID,
                    IdentityHint.of(IdentitySource.HEADER, commandBuilder.spaceId),
                    IdentityHint(IdentitySource.UPSTREAM, domainEvent.spaceId),
                )
            )
            .upstream(domainEvent)
            .header {
                messagePropagator.propagate(it, domainEvent, this@StatelessSagaFunction)
            }
        @Suppress("UNCHECKED_CAST")
        return commandMessageFactory.create<Any>(commandBuilder) as Mono<CommandMessage<*>>
    }

    /**
     * A command message the saga function built itself keeps its IDs, except a request ID it did not set (equal to
     * the message ID, the default): that one becomes the derived `"<event ID>-<index>"`, so a retry is a duplicate
     * request. Its aggregate ID is kept: a generated one cannot be told from a chosen one; return a body or a
     * [CommandBuilder] to get a derived aggregate ID.
     */
    private fun CommandMessage<*>.withDerivedRequestId(domainEvent: DomainEvent<*>, index: Int): CommandMessage<*> {
        if (requestId == id && this is SimpleCommandMessage<*>) {
            return copy(requestId = SagaCommandIds.requestId(domainEvent, index), header = header.copy())
        }
        return if (header.isReadOnly) copy() else this
    }

    /** A command that names no aggregate gets one derived from the event, see [SagaCommandIds.aggregateId]. */
    private fun CommandBuilder.derivedAggregateIdIfAbsent(domainEvent: DomainEvent<*>, index: Int): CommandBuilder {
        if (aggregateId != null) {
            return this
        }
        val metadata = body.javaClass.commandMetadata()
        if (metadata.aggregateIdGetter?.get(body) != null) {
            return this
        }
        val target = namedAggregate ?: metadata.namedAggregateGetter?.getNamedAggregate(body) ?: return this
        val derived = SagaCommandIds.aggregateId(domainEvent, this@StatelessSagaFunction, index, target) ?: return this
        return aggregateId(derived)
    }

    override fun toString(): String = "StatelessSagaFunction(actual=$delegate)"
}
