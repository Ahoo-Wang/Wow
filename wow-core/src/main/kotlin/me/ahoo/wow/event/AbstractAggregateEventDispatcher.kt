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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.dispatcher.AggregateMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism.toGroupKey
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractAggregateEventDispatcher<E : MessageExchange<*, DomainEventStream>> :
    AggregateMessageDispatcher<E>() {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    abstract val functionRegistrar:
        MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>
    abstract val eventHandler: EventHandler

    override fun E.toGroupKey(): Int {
        return message.toGroupKey(parallelism)
    }

    override fun handleExchange(exchange: E): Mono<Void> {
        return Flux.fromIterable(exchange.message)
            .concatMap { handleEvent(exchange, it) }
            .finallyAck(exchange)
    }

    private fun handleEvent(
        exchange: E,
        event: DomainEvent<*>
    ): Mono<Void> {
        val functions = functionRegistrar.supportedFunctions(event)
            .filter {
                event.match(it)
            }.toSet()
        if (functions.isEmpty()) {
            log.debug {
                "Not find any functions.Ignore this event:[${event.toJsonString()}]."
            }
            return Mono.empty()
        }
        return Flux.fromIterable(functions)
            .flatMap { function ->
                val eventExchange = exchange.createEventExchange(event)
                    .setFunction(function)
                eventHandler.handle(eventExchange)
            }.then()
    }

    abstract fun E.createEventExchange(event: DomainEvent<*>): DomainEventExchange<*>
}
