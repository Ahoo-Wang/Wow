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
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.rest.RestCommandGateway
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedBoundedContext
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux

class StatelessSagaFunction(
    val actual: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    private val commandGateway: CommandGateway,
    private val restCommandGateway: RestCommandGateway?
) :
    MessageFunction<Any, DomainEventExchange<*>, Mono<CommandStream>> {
    override val contextName: String = actual.contextName
    override val name: String = actual.name
    override val processor: Any = actual.processor
    override val supportedType: Class<*> = actual.supportedType
    override val supportedTopics: Set<NamedAggregate> = actual.supportedTopics
    override val functionKind: FunctionKind = actual.functionKind
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
        return actual.getAnnotation(annotationClass)
    }

    override fun invoke(exchange: DomainEventExchange<*>): Mono<CommandStream> {
        return actual.invoke(exchange)
            .map {
                toCommandStream(exchange.message, it)
            }
            .flatMap { commandStream ->
                exchange.setCommandStream(commandStream)
                commandStream
                    .toFlux()
                    .concatMap {
                        sendCommand(commandStream.domainEvent, it)
                    }
                    .then(Mono.just(commandStream))
            }
    }

    private fun toCommandStream(domainEvent: DomainEvent<*>, handleResult: Any): CommandStream {
        if (handleResult !is Iterable<*>) {
            return DefaultCommandStream(
                domainEvent = domainEvent,
                commands = listOf(SagaCommand(handleResult)),
            )
        }
        val commands = handleResult.mapIndexed { index, command ->
            requireNotNull(command)
            SagaCommand(command, index)
        }

        return DefaultCommandStream(domainEvent, commands)
    }

    private fun sendCommand(domainEvent: DomainEvent<*>, sagaCommand: SagaCommand<*>): Mono<Void> {
        if (sagaCommand.command is CommandMessage<*>) {
            return commandGateway.send(sagaCommand.command)
        }
        val requestId = "${domainEvent.id}-${sagaCommand.index}"
        val tenantId = domainEvent.aggregateId.tenantId
        if (restCommandGateway == null) {
            val command = sagaCommand.command.toCommandMessage(requestId = requestId, tenantId = tenantId)
            return commandGateway.send(command)
        }

        if (sagaCommand.command is RestCommandGateway.CommandRequest) {
            return restCommandGateway.send(sagaCommand.command).then()
        }

        val commandContext = sagaCommand.command.javaClass.requiredNamedBoundedContext()
        if (commandContext.isSameBoundedContext(this)) {
            val command = sagaCommand.command.toCommandMessage(requestId = requestId, tenantId = tenantId)
            return commandGateway.send(command)
        }

        val commandRequest = RestCommandGateway.CommandRequest(
            body = sagaCommand.command,
            requestId = requestId,
            tenantId = tenantId,
            context = commandContext.contextName
        )
        return restCommandGateway.send(commandRequest).then()
    }

    override fun toString(): String {
        return "StatelessSagaFunction(actual=$actual)"
    }
}
