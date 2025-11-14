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
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatcher for processing state events within a specific aggregate context.
 *
 * This class handles the distribution and processing of state events for a particular
 * named aggregate. It extends AbstractAggregateEventDispatcher to provide concrete
 * implementation for state event processing, including access to aggregate state.
 *
 * @property namedAggregate The named aggregate this dispatcher handles
 * @property name The name of this dispatcher (default: derived from aggregate name)
 * @property parallelism The level of parallelism for processing (default: DEFAULT_PARALLELISM)
 * @property messageFlux The flux of state event exchanges to process
 * @property functionRegistrar The registrar containing event processing functions
 * @property eventHandler The handler for processing individual events
 * @property scheduler The scheduler for managing event processing concurrency
 *
 * @constructor Creates a new AggregateStateEventDispatcher with the specified parameters
 *
 * @see AbstractAggregateEventDispatcher
 * @see NamedAggregate
 * @see StateEventExchange
 * @see MessageFunctionRegistrar
 * @see EventHandler
 * @see Scheduler
 */
@Suppress("LongParameterList")
class AggregateStateEventDispatcher(
    override val namedAggregate: NamedAggregate,
    override val name: String =
        "${namedAggregate.aggregateName}-${AggregateStateEventDispatcher::class.simpleName!!}",
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val messageFlux: Flux<StateEventExchange<*>>,
    override val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    override val eventHandler: EventHandler,
    override val scheduler: Scheduler
) : AbstractAggregateEventDispatcher<StateEventExchange<*>>() {
    /**
     * Creates a state domain event exchange from a state event exchange and domain event.
     *
     * This method wraps a domain event in a SimpleStateDomainEventExchange, providing
     * access to the aggregate state and copying attributes from the parent exchange.
     *
     * @param event The domain event to create an exchange for
     * @return A new SimpleStateDomainEventExchange containing the event and state
     *
     * @see SimpleStateDomainEventExchange
     * @see DomainEvent
     * @see ReadOnlyStateAggregate
     */
    override fun StateEventExchange<*>.createEventExchange(event: DomainEvent<*>): DomainEventExchange<*> =
        SimpleStateDomainEventExchange(
            state = message,
            message = event,
            attributes = ConcurrentHashMap(attributes),
        )
}
