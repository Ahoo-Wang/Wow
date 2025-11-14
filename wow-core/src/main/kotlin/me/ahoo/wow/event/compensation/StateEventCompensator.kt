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

package me.ahoo.wow.event.compensation

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import reactor.core.publisher.Mono

/**
 * Compensator for state events.
 *
 * This class handles compensation operations for state events by reconstructing
 * state from event sourcing and resending state events to the state event bus
 * with compensation metadata.
 *
 * @property stateAggregateFactory Factory for creating state aggregates
 * @property eventStore The event store for retrieving historical events
 * @property stateEventBus The state event bus for publishing compensated state events
 *
 * @constructor Creates a new StateEventCompensator with the specified dependencies
 *
 * @param stateAggregateFactory The factory for creating state aggregates
 * @param eventStore The event store to use for loading events
 * @param stateEventBus The state event bus to use for sending compensated events
 *
 * @see EventCompensator
 * @see StateEvent
 * @see StateAggregateFactory
 * @see EventStore
 * @see StateEventBus
 * @see CompensationTarget
 */
class StateEventCompensator(
    private val stateAggregateFactory: StateAggregateFactory,
    private val eventStore: EventStore,
    private val stateEventBus: StateEventBus
) : EventCompensator<StateEvent<*>> {
    /**
     * Compensates a state event by marking it for compensation and resending.
     *
     * This method applies compensation metadata to the state event and publishes
     * it to the state event bus for reprocessing.
     *
     * @param eventStream The state event to compensate
     * @param target The compensation target specifying the compensation context
     * @return A Mono that completes when the compensated event is sent
     *
     * @see StateEvent.withCompensation
     * @see StateEventBus.send
     * @see CompensationTarget
     */
    override fun compensate(
        eventStream: StateEvent<*>,
        target: CompensationTarget
    ): Mono<Void> {
        eventStream.withCompensation(target)
        return stateEventBus.send(eventStream)
    }

    /**
     * Resends a range of state events by reconstructing state from event sourcing.
     *
     * This method loads events from the event store, reconstructs the aggregate state
     * through event sourcing, converts events to state events, and resends them
     * within the specified version range.
     *
     * @param aggregateId The aggregate ID to resend state events for
     * @param headVersion The starting version (inclusive) of events to resend
     * @param tailVersion The ending version (inclusive) of events to resend
     * @param target The compensation target for the resend operation
     * @return A Mono containing the count of state events that were resent
     *
     * @see StateAggregateFactory.createAsMono
     * @see EventStore.load
     * @see me.ahoo.wow.modeling.state.StateAggregate.onSourcing
     * @see me.ahoo.wow.event.DomainEventStream.toStateEvent
     * @see compensate
     * @see AggregateId
     * @see CompensationTarget
     */
    override fun resend(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
        target: CompensationTarget
    ): Mono<Long> {
        val aggregateMetadata =
            aggregateId
                .requiredAggregateType<Any>()
                .aggregateMetadata<Any, Any>()
        return stateAggregateFactory.createAsMono(aggregateMetadata.state, aggregateId).flatMap { stateAggregate ->
            eventStore
                .load(
                    aggregateId = aggregateId,
                    tailVersion = tailVersion,
                ).map {
                    stateAggregate.onSourcing(it)
                    it.toStateEvent(stateAggregate)
                }.filter {
                    it.version in headVersion..tailVersion
                }.concatMap {
                    compensate(it, target).thenReturn(it.aggregateId)
                }.count()
        }
    }
}
