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
import me.ahoo.wow.command.commandSentSignal
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.extractWaitPlan
import me.ahoo.wow.command.wait.notifyAndForget
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.ioc.getService
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionAccessor
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.net.URLEncoder

/**
 * A stateless saga function that processes domain events and generates command streams.
 * This function wraps a delegate message function and extends its behavior to handle command execution
 * and collection into a [CommandStream] for stateless saga processing.
 *
 * @param delegate The underlying message function that handles the domain event processing.
 * @param commandGateway The gateway used to send commands.
 * @param commandMessageFactory The factory for creating command messages.
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
    override val processorName: String = delegate.processorName
    override val supportedType: Class<*> = delegate.supportedType
    override val supportedTopics: Set<NamedAggregate> = delegate.supportedTopics
    override val functionKind: FunctionKind = delegate.functionKind
    private val method = (delegate.getOriginalDelegate() as? MessageFunctionAccessor<*, *, *>)?.metadata?.accessor?.method
    private val signature = name + (method?.parameterTypes?.map { it.name } ?: listOf(supportedType.name))
        .joinToString(",", "(", ")")
    private val functionId = listOf(contextName, processorName, signature)
        .joinToString(":") { URLEncoder.encode(it, Charsets.UTF_8) }
    private val commandsKey = "__SAGA_COMMANDS__$functionId"

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = delegate.getAnnotation(annotationClass)

    override fun invoke(exchange: DomainEventExchange<*>): Mono<CommandStream> = Mono.defer {
        val commands = exchange.getAttribute<List<PendingCommand>>(commandsKey)?.toMono()
            ?: delegate.invoke(exchange)
                .flatMapMany { toCommandFlux(exchange.message, it) }
                .collectList()
                .doOnNext { exchange.setAttribute(commandsKey, it) }
        commands.flatMapMany { Flux.fromIterable(it) }
            .concatMap { pending ->
                if (pending.sent) {
                    Mono.just(pending.command)
                } else {
                    recoverStoredCommand(exchange, pending)
                        .switchIfEmpty(
                            Mono.defer {
                                commandGateway.send(pending.command).thenReturn(pending.command)
                                    .onErrorResume(DuplicateRequestIdException::class.java) { error ->
                                        recoverStoredCommand(exchange, pending).switchIfEmpty(Mono.error(error))
                                    }
                            }
                        ).doOnNext {
                            pending.command = it
                            pending.sent = true
                        }
                }
            }.collectList()
            .map { DefaultCommandStream(exchange.message.id, it).also(exchange::setCommandStream) }
    }

    private fun recoverStoredCommand(
        exchange: DomainEventExchange<*>,
        pending: PendingCommand,
    ): Mono<CommandMessage<*>> {
        val provider = exchange.getServiceProvider() ?: return Mono.empty()
        val eventStore = provider.getService<EventStore>() ?: return Mono.empty()
        val command = pending.command
        return eventStore.loadByRequestIds(
            command.aggregateId,
            setOfNotNull(command.requestId, pending.legacyRequestId)
        )
            .collectList()
            .flatMap { records ->
                val stored = records.firstOrNull { it.requestId == command.requestId }
                if (stored == null) {
                    if (records.isEmpty()) return@flatMap Mono.empty()
                    return@flatMap Mono.error(
                        DuplicateRequestIdException(
                            command.aggregateId,
                            records.first().requestId,
                            "Ambiguous legacy Saga request ID[${records.first().requestId}] for [$functionId]. " +
                                "Reconcile the previous command before specifying an explicit requestId.",
                        )
                    )
                }
                val restored = command.restoreIdentity(stored)
                notifyStoredCommand(restored, provider.getService<CommandWaitNotifier>())
                Mono.just(restored)
            }
    }

    private fun CommandMessage<*>.restoreIdentity(stored: DomainEventStream): CommandMessage<*> =
        SimpleCommandMessage(
            id = stored.commandId,
            requestId = stored.requestId,
            body = body,
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            header = header.copy(),
            aggregateVersion = aggregateVersion,
            name = name,
            isCreate = isCreate,
            allowCreate = allowCreate,
            isVoid = isVoid,
            createTime = createTime,
        )

    private fun notifyStoredCommand(
        command: CommandMessage<*>,
        notifier: CommandWaitNotifier?,
    ) {
        notifier ?: return
        val waitPlan = command.header.extractWaitPlan() ?: return
        // A stored event proves acceptance. Later stages still require their actual notifications.
        notifier.notifyAndForget(waitPlan, command.commandSentSignal(waitPlan.waitCommandId))
    }

    @Suppress("UNCHECKED_CAST")
    private fun toCommandFlux(
        domainEvent: DomainEvent<*>,
        handleResult: Any
    ): Publisher<PendingCommand> {
        if (handleResult !is Iterable<*>) {
            return toCommand(domainEvent = domainEvent, singleResult = handleResult)
        }
        return Flux
            .fromIterable(handleResult as Iterable<Any>)
            .index()
            .concatMap {
                toCommand(domainEvent = domainEvent, singleResult = it.t2, index = it.t1.toInt())
            }
    }

    private fun toCommand(
        domainEvent: DomainEvent<*>,
        singleResult: Any,
        index: Int = 0
    ): Mono<PendingCommand> {
        if (singleResult is CommandMessage<*>) {
            singleResult.header.propagate(domainEvent)
            return PendingCommand(singleResult).toMono()
        }
        val commandBuilder = singleResult as? CommandBuilder ?: singleResult.commandBuilder()
        val legacyRequestId = "${domainEvent.id}-$index".takeIf { commandBuilder.requestId == null }
        commandBuilder
            .requestIdIfAbsent("saga:${domainEvent.id}:$functionId:$index")
            .tenantIdIfAbsent(domainEvent.aggregateId.tenantId)
            .spaceIdIfAbsent(domainEvent.spaceId)
            .upstream(domainEvent)
            .header {
                it.propagate(domainEvent)
            }
        return commandMessageFactory.create<Any>(commandBuilder).map { PendingCommand(it, legacyRequestId) }
    }

    private class PendingCommand(
        var command: CommandMessage<*>,
        val legacyRequestId: String? = null,
        var sent: Boolean = false,
    )

    override fun toString(): String = "StatelessSagaFunction(actual=$delegate)"
}
