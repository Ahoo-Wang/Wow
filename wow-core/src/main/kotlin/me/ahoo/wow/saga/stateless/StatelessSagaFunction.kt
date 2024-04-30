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
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.command.factory.CommandOptions
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class StatelessSagaFunction(
    val actual: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    private val commandGateway: CommandGateway,
    private val commandMessageFactory: CommandMessageFactory
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
            .flatMapMany {
                toCommandFlux(exchange.message, it)
            }
            .concatMap {
                commandGateway.send(it).thenReturn(it)
            }.collectList()
            .map {
                val commandStream = DefaultCommandStream(exchange.message.id, it)
                exchange.setCommandStream(commandStream)
                commandStream
            }
    }

    private fun toCommandFlux(domainEvent: DomainEvent<*>, handleResult: Any): Publisher<CommandMessage<*>> {
        if (handleResult !is Iterable<*>) {
            return toCommand(domainEvent = domainEvent, singleResult = handleResult)
        }
        return Flux.fromIterable(handleResult)
            .index()
            .flatMap {
                toCommand(domainEvent = domainEvent, singleResult = it.t2, index = it.t1.toInt())
            }
    }

    private fun toCommand(domainEvent: DomainEvent<*>, singleResult: Any, index: Int = 0): Mono<CommandMessage<*>> {
        if (singleResult is CommandMessage<*>) {
            return singleResult.toMono()
        }
        val commandOptions = CommandOptions.builder()
            .requestId("${domainEvent.id}-$index")
            .tenantId(domainEvent.aggregateId.tenantId)
        @Suppress("UNCHECKED_CAST")
        return commandMessageFactory.create(singleResult, commandOptions) as Mono<CommandMessage<*>>
    }

    override fun toString(): String {
        return "StatelessSagaFunction(actual=$actual)"
    }
}
