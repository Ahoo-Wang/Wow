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
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.handler.Handler
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.naming.annotation.asName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

abstract class AbstractEventDispatcher<R : Mono<*>>(
    private val domainEventBus: DomainEventBus,
    private val functionRegistrar: AbstractEventFunctionRegistrar<R>,
    private val eventHandler: Handler<DomainEventExchange<Any>>,
) :
    AbstractMessageDispatcher<Void>() {

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AbstractEventDispatcher::class.java)
    }

    override val topics: Set<NamedAggregate>
        get() = functionRegistrar.namedAggregates
    protected abstract val scheduler: Scheduler

    override fun start() {
        domainEventBus
            .receive(topics)
            .writeReceiverGroup(name)
            .parallel()
            .runOn(scheduler)
            .flatMap { handle(it) }
            .subscribe(this)
    }

    private fun handle(exchange: EventStreamExchange): Mono<Void> {
        return Flux.fromIterable(exchange.message)
            .concatMap { handleEvent(it) }
            .doFinally { exchange.acknowledge() }
            .then()
    }

    private fun handleEvent(
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
                    SimpleDomainEventExchange(event, function) as DomainEventExchange<Any>
                eventHandler.handle(eventExchange)
            }.then()
    }
}
