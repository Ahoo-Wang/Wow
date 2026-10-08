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
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Dispatcher for processing the domain events of the aggregates of one bounded context.
 *
 * This class handles the distribution and processing of domain event streams
 * received through one receiver for all of these aggregates. It extends AbstractAggregateEventDispatcher
 * to provide concrete implementation for event stream processing.
 *
 * @property namedAggregates The aggregates this dispatcher handles, all of one bounded context
 * @property name The name of this dispatcher (default: derived from the bounded context name)
 * @property messageFlux The flux of event stream exchanges to process
 * @property functionRegistrar The registrar containing event processing functions
 * @property eventHandler The handler for processing individual events
 * @param messageReadiness Completion of asynchronous message-source setup when
 * this dispatcher is registered directly with a runtime
 * @param processingAdmission Explicit transport-processing gate opened by
 * [start]
 * @param processingQuiescence Logical transport gate closed by [quiesce]
 * @param metrics Instance-scoped metrics recorder for dispatcher operations
 *
 * @constructor Creates a new AggregateEventDispatcher with the specified parameters
 *
 * @see AbstractAggregateEventDispatcher
 * @see NamedAggregate
 * @see me.ahoo.wow.event.EventStreamExchange
 * @see MessageFunctionRegistrar
 * @see EventHandler
 */
internal class AggregateEventDispatcher(
    override val namedAggregates: Set<NamedAggregate>,
    override val name: String =
        "${namedAggregates.first().contextName}-${AggregateEventDispatcher::class.simpleName!!}",
    override val messageFlux: Flux<EventStreamExchange>,
    override val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    override val eventHandler: EventHandler,
    messageReadiness: Mono<Void> = Mono.empty(),
    processingAdmission: () -> Unit = {},
    processingQuiescence: () -> Unit = {},
    metrics: WowMetrics = WowMetrics.NONE,
) : AbstractAggregateEventDispatcher<EventStreamExchange>(
    messageReadiness = messageReadiness,
    processingAdmission = processingAdmission,
    processingQuiescence = processingQuiescence,
    metrics = metrics,
) {
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
            attributes = attributes.copyAttributes(),
        )

    /** The per-aggregate dispatcher name 9.2 reported, kept as the metric tag. */
    override fun metricProcessorName(namedAggregate: NamedAggregate): String =
        "${namedAggregate.aggregateName}-${AggregateEventDispatcher::class.simpleName!!}"
}
