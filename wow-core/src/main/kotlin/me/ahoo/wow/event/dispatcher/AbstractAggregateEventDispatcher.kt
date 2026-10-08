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

package me.ahoo.wow.event.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.dispatcher.AggregateDispatcher
import me.ahoo.wow.messaging.dispatcher.toMailboxKey
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.messaging.handler.acknowledgementWithheldBy
import me.ahoo.wow.messaging.handler.withholdAcknowledgement
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract base class for aggregate event dispatchers.
 *
 * This class provides the foundation for dispatching domain events to appropriate
 * handlers within an aggregate context. It manages the processing of event streams,
 * filtering events through registered functions, and coordinating with event handlers.
 *
 * @param E The type of message exchange being handled
 * @param messageReadiness Completes when the message transport can retain new work
 * @param processingAdmission Opens transport processing after dispatcher demand
 * @param processingQuiescence Revokes transport processing before source cancellation
 * @param metrics Instance-scoped metrics recorder for dispatcher operations
 *
 * @see AggregateDispatcher
 * @see MessageExchange
 * @see me.ahoo.wow.event.DomainEventStream
 * @see MessageFunctionRegistrar
 * @see EventHandler
 */
internal abstract class AbstractAggregateEventDispatcher<E : MessageExchange<*, DomainEventStream>>(
    messageReadiness: Mono<Void> = Mono.empty(),
    processingAdmission: () -> Unit = {},
    processingQuiescence: () -> Unit = {},
    metrics: WowMetrics,
) : AggregateDispatcher<E>(
    messageReadiness = messageReadiness,
    processingAdmission = processingAdmission,
    processingQuiescence = processingQuiescence,
    metrics = metrics,
) {
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
     * Events of one aggregate run in order: the mailbox key is the aggregate ID ([toMailboxKey]:
     * bounded context, aggregate name and ID, not the tenant, as in 9.2), since one dispatcher serves several aggregates of a context and
     * an ID (for example one derived by a saga) can be shared across aggregate types.
     */
    override fun E.mailboxKey(): Any = message.aggregateId.toMailboxKey()

    /**
     * Handles a message exchange by processing all events in the stream.
     *
     * This method iterates through all events in the stream, processes each one,
     * and acknowledges the exchange upon completion.
     *
     * @param exchange The message exchange containing the event stream
     * @return A Mono that completes when all events are processed
     *
     * Each event is dispatched through the private event handler.
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
        return Flux
            .fromIterable(functions)
            .flatMap { function ->
                val eventExchange = exchange.createEventExchange(event).setFunction(function)
                eventHandler.handle(eventExchange)
                    .doOnTerminate {
                        eventExchange.acknowledgementWithheldBy()?.let(exchange::withholdAcknowledgement)
                    }
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

/**
 * The attributes of one function's event exchange: a copy of the stream exchange's. An empty map (the usual case: the
 * local delivery ticket is already taken) is not copied, so the new map allocates its table on its first entry instead
 * of presizing it for the copy.
 */
internal fun Map<String, Any>.copyAttributes(): MutableMap<String, Any> =
    if (isEmpty()) ConcurrentHashMap() else ConcurrentHashMap(this)
