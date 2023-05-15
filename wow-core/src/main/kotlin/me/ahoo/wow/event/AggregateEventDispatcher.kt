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

package me.ahoo.wow.event

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.dispatcher.AggregateMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.asGroupKey
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.naming.annotation.asName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

@Suppress("LongParameterList")
class AggregateEventDispatcher<R : Mono<*>>(
    override val namedAggregate: NamedAggregate,
    override val name: String =
        "${namedAggregate.aggregateName}-${AggregateEventDispatcher::class.simpleName!!}",
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val messageFlux: Flux<EventStreamExchange>,
    private val functionRegistrar: AbstractEventFunctionRegistrar<R>,
    private val eventHandler: EventHandler,
    override val scheduler: Scheduler,
) : AggregateMessageDispatcher<EventStreamExchange>() {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(AggregateEventDispatcher::class.java)
    }

    override fun EventStreamExchange.asGroupKey(): Int {
        return message.asGroupKey(parallelism)
    }

    override fun handleExchange(exchange: EventStreamExchange): Mono<Void> {
        return Flux.fromIterable(exchange.message)
            .concatMap { handleEvent(exchange, it) }
            .doFinally { exchange.acknowledge() }
            .then()
    }

    private fun handleEvent(
        exchange: EventStreamExchange,
        event: DomainEvent<*>
    ): Mono<Void> {
        val eventType: Class<*> = event.body.javaClass
        val functions = functionRegistrar.getFunctions(eventType)
            .filter {
                if (!it.supportedTopics.contains(event.aggregateId.materialize())) {
                    return@filter false
                }
                return@filter event.shouldHandle(it.processor.javaClass.asName())
            }
        if (functions.isEmpty()) {
            if (log.isDebugEnabled) {
                log.debug(
                    "{} eventType[{}] not find any functions.Ignore this event:[{}].",
                    event.aggregateId,
                    eventType,
                    event
                )
            }
            return Mono.empty()
        }
        return Flux.fromIterable(functions)
            .flatMap { function ->
                @Suppress("UNCHECKED_CAST")
                val eventExchange: DomainEventExchange<Any> =
                    SimpleDomainEventExchange(
                        message = event,
                        eventFunction = function,
                        attributes = exchange.attributes
                    ) as DomainEventExchange<Any>
                eventHandler.handle(eventExchange)
            }.then()
    }
}
