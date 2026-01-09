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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatcher for processing domain events within a specific aggregate context.
 *
 * This class handles the distribution and processing of domain event streams
 * for a particular named aggregate. It extends AbstractAggregateEventDispatcher
 * to provide concrete implementation for event stream processing.
 *
 * @property namedAggregate The named aggregate this dispatcher handles
 * @property name The name of this dispatcher (default: derived from aggregate name)
 * @property parallelism The level of parallelism for processing (default: DEFAULT_PARALLELISM)
 * @property messageFlux The flux of event stream exchanges to process
 * @property functionRegistrar The registrar containing event processing functions
 * @property eventHandler The handler for processing individual events
 * @property scheduler The scheduler for managing event processing concurrency
 *
 * @constructor Creates a new AggregateEventDispatcher with the specified parameters
 *
 * @see AbstractAggregateEventDispatcher
 * @see NamedAggregate
 * @see me.ahoo.wow.event.EventStreamExchange
 * @see MessageFunctionRegistrar
 * @see EventHandler
 */
class AggregateEventDispatcher(
    override val name: String =
        "${namedAggregate.aggregateName}-${AggregateEventDispatcher::class.simpleName!!}",
    override val namedAggregate: NamedAggregate,
    override val messageFlux: Flux<EventStreamExchange>,
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    override val eventHandler: EventHandler,
) : AbstractAggregateEventDispatcher<EventStreamExchange>() {
    /**
     * Creates a domain event exchange from an event stream exchange and domain event.
     *
     * This method wraps a domain event in a SimpleDomainEventExchange, copying
     * attributes from the parent event stream exchange.
     *
     * @param event The domain event to create an exchange for
     * @return A new SimpleDomainEventExchange containing the event
     *
     * @see me.ahoo.wow.event.SimpleDomainEventExchange
     * @see DomainEvent
     */
    override fun EventStreamExchange.createEventExchange(event: DomainEvent<*>): DomainEventExchange<*> =
        SimpleDomainEventExchange(
            message = event,
            attributes = ConcurrentHashMap(attributes),
        )
}
