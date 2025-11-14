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

/**
 * Abstract base class for aggregate event dispatchers.
 *
 * This class provides the foundation for dispatching domain events to appropriate
 * handlers within an aggregate context. It manages the processing of event streams,
 * filtering events through registered functions, and coordinating with event handlers.
 *
 * @param E The type of message exchange being handled
 *
 * @property functionRegistrar The registrar containing event processing functions
 * @property eventHandler The handler responsible for processing individual events
 *
 * @see AggregateMessageDispatcher
 * @see MessageExchange
 * @see DomainEventStream
 * @see MessageFunctionRegistrar
 * @see EventHandler
 */
abstract class AbstractAggregateEventDispatcher<E : MessageExchange<*, DomainEventStream>> : AggregateMessageDispatcher<E>() {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The registrar containing event processing functions.
     */
    abstract val functionRegistrar:
        MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>

    /**
     * The handler responsible for processing individual events.
     */
    abstract val eventHandler: EventHandler

    /**
     * Converts the exchange to a group key for parallel processing.
     *
     * @param exchange The message exchange
     * @return The group key for partitioning events
     *
     * @see MessageParallelism.toGroupKey
     */
    override fun E.toGroupKey(): Int = message.toGroupKey(parallelism)

    /**
     * Handles a message exchange by processing all events in the stream.
     *
     * This method iterates through all events in the stream, processes each one,
     * and acknowledges the exchange upon completion.
     *
     * @param exchange The message exchange containing the event stream
     * @return A Mono that completes when all events are processed
     *
     * @see handleEvent
     * @see ExchangeAck.finallyAck
     */
    override fun handleExchange(exchange: E): Mono<Void> =
        Flux
            .fromIterable(exchange.message)
            .concatMap { handleEvent(exchange, it) }
            .finallyAck(exchange)

    /**
     * Handles an individual domain event within the exchange context.
     *
     * This method finds all registered functions that can handle the event,
     * creates event exchanges for each function, and invokes the event handler.
     *
     * @param exchange The parent message exchange
     * @param event The domain event to process
     * @return A Mono that completes when the event is processed
     *
     * @see MessageFunctionRegistrar.supportedFunctions
     * @see EventHandler.handle
     * @see createEventExchange
     */
    private fun handleEvent(
        exchange: E,
        event: DomainEvent<*>
    ): Mono<Void> {
        val functions =
            functionRegistrar
                .supportedFunctions(event)
                .filter {
                    event.match(it)
                }.toSet()
        if (functions.isEmpty()) {
            log.debug {
                "Not find any functions.Ignore this event:[${event.toJsonString()}]."
            }
            return Mono.empty()
        }
        return Flux
            .fromIterable(functions)
            .flatMap { function ->
                val eventExchange =
                    exchange
                        .createEventExchange(event)
                        .setFunction(function)
                eventHandler.handle(eventExchange)
            }.then()
    }

    /**
     * Creates a domain event exchange for the given event.
     *
     * @param event The domain event to create an exchange for
     * @return A new DomainEventExchange for processing the event
     *
     * @see DomainEventExchange
     * @see DomainEvent
     */
    abstract fun E.createEventExchange(event: DomainEvent<*>): DomainEventExchange<*>
}
