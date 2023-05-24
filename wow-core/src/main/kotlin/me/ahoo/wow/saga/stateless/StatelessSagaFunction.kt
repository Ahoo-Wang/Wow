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
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux

class StatelessSagaFunction(
    val actual: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    private val commandBus: CommandBus
) :
    MessageFunction<Any, DomainEventExchange<*>, Mono<CommandStream>> {

    override val processor: Any
        get() = actual.processor
    override val supportedType: Class<*>
        get() = actual.supportedType
    override val supportedTopics: Set<Any>
        get() = actual.supportedTopics

    override fun handle(exchange: DomainEventExchange<*>): Mono<CommandStream> {
        return actual.handle(exchange)
            .map {
                asCommandStream(exchange.message, it)
            }
            .flatMap { commandStream ->
                commandStream
                    .toFlux()
                    .concatMap {
                        commandBus.send(it)
                    }
                    .then(Mono.just(commandStream))
            }
    }

    private fun asCommandStream(domainEvent: DomainEvent<*>, handleResult: Any): CommandStream {
        if (handleResult !is Iterable<*>) {
            return DefaultCommandStream(
                domainEvent.id,
                listOf(asCommand(domainEvent, handleResult)),
            )
        }
        val commands = mutableListOf<CommandMessage<*>>()
        handleResult.forEach {
            requireNotNull(it)
            commands.add(asCommand(domainEvent, it))
        }
        return DefaultCommandStream(domainEvent.id, commands)
    }

    private fun asCommand(domainEvent: DomainEvent<*>, singleResult: Any): CommandMessage<*> {
        if (singleResult is CommandMessage<*>) {
            return singleResult
        }
        return singleResult.asCommandMessage(requestId = domainEvent.id, tenantId = domainEvent.aggregateId.tenantId)
    }

    override fun toString(): String {
        return "StatelessSagaFunction(actual=$actual)"
    }
}
