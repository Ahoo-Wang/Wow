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

package me.ahoo.wow.command.wait

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.command.dispatcher.CommandFilter
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import reactor.core.publisher.Mono

/**
 * Abstract base class for filters that notify wait strategies about command processing completion.
 * These filters intercept message processing pipelines and send notifications to waiting clients
 * when specific processing stages are reached.
 *
 * @param T The type of message exchange being filtered.
 * @param M The type of message in the exchange.
 * @param processingStage The command stage this filter notifies about.
 * @param commandWaitNotifier The notifier used to send wait signals.
 */
abstract class AbstractNotifierFilter<T : MessageExchange<*, M>, M>(
    private val processingStage: CommandStage,
    private val commandWaitNotifier: CommandWaitNotifier
) : ExchangeFilter<T> where M : Message<*, *>, M : CommandId, M : NamedBoundedContext, M : AggregateIdCapable {
    override fun filter(
        exchange: T,
        next: FilterChain<T>
    ): Mono<Void> = next.filter(exchange)
        .thenNotifyAndForget(commandWaitNotifier, processingStage, exchange)
}

/**
 * Filter that notifies wait strategies when command processing is complete.
 * Intercepts the command dispatcher pipeline to send PROCESSED stage notifications.
 *
 * @param commandWaitNotifier The notifier for sending wait signals.
 */
@Order(ORDER_FIRST)
class ProcessedNotifierFilter(
    commandWaitNotifier: CommandWaitNotifier
) : CommandFilter,
    AbstractNotifierFilter<ServerCommandExchange<*>, CommandMessage<*>>(CommandStage.PROCESSED, commandWaitNotifier)

/**
 * Filter that notifies wait strategies when aggregate snapshots are generated.
 * Intercepts the snapshot dispatcher pipeline to send SNAPSHOT stage notifications.
 *
 * @param commandWaitNotifier The notifier for sending wait signals.
 */
@FilterType(SnapshotDispatcher::class)
@Order(ORDER_FIRST)
class SnapshotNotifierFilter(
    commandWaitNotifier: CommandWaitNotifier
) : AbstractNotifierFilter<StateEventExchange<*>, StateEvent<*>>(CommandStage.SNAPSHOT, commandWaitNotifier)

/**
 * Filter that notifies wait strategies when projections are updated.
 * Intercepts the projection dispatcher pipeline to send PROJECTED stage notifications.
 *
 * @param commandWaitNotifier The notifier for sending wait signals.
 */
@FilterType(ProjectionDispatcher::class)
@Order(ORDER_FIRST)
class ProjectedNotifierFilter(
    commandWaitNotifier: CommandWaitNotifier
) : AbstractNotifierFilter<DomainEventExchange<Any>, DomainEvent<*>>(CommandStage.PROJECTED, commandWaitNotifier)

/**
 * Filter that notifies wait strategies when domain events are handled by event processors.
 * Intercepts the domain event dispatcher pipeline to send EVENT_HANDLED stage notifications.
 *
 * @param commandWaitNotifier The notifier for sending wait signals.
 */
@FilterType(DomainEventDispatcher::class)
@Order(ORDER_FIRST)
class EventHandledNotifierFilter(
    commandWaitNotifier: CommandWaitNotifier
) : AbstractNotifierFilter<DomainEventExchange<Any>, DomainEvent<*>>(CommandStage.EVENT_HANDLED, commandWaitNotifier)

/**
 * Filter that notifies wait strategies when domain events are handled by sagas.
 * Intercepts the stateless saga dispatcher pipeline to send SAGA_HANDLED stage notifications.
 *
 * @param commandWaitNotifier The notifier for sending wait signals.
 */
@FilterType(StatelessSagaDispatcher::class)
@Order(ORDER_FIRST)
class SagaHandledNotifierFilter(
    commandWaitNotifier: CommandWaitNotifier
) : AbstractNotifierFilter<DomainEventExchange<Any>, DomainEvent<*>>(CommandStage.SAGA_HANDLED, commandWaitNotifier)
