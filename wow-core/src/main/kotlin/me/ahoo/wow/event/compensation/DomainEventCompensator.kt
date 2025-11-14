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
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensator
import reactor.core.publisher.Mono

/**
 * Compensator for domain event streams.
 *
 * This class handles compensation operations for domain events by resending
 * event streams to the event bus with compensation metadata. It can compensate
 * individual event streams or resend ranges of events from the event store.
 *
 * @property eventStore The event store for retrieving historical events
 * @property eventBus The domain event bus for publishing compensated events
 *
 * @constructor Creates a new DomainEventCompensator with the specified dependencies
 *
 * @param eventStore The event store to use for loading events
 * @param eventBus The event bus to use for sending compensated events
 *
 * @see EventCompensator
 * @see DomainEventStream
 * @see EventStore
 * @see DomainEventBus
 * @see CompensationTarget
 */
class DomainEventCompensator(
    private val eventStore: EventStore,
    private val eventBus: DomainEventBus
) : EventCompensator<DomainEventStream> {
    /**
     * Compensates an event stream by marking it for compensation and resending.
     *
     * This method applies compensation metadata to the event stream and publishes
     * it to the event bus for reprocessing.
     *
     * @param eventStream The event stream to compensate
     * @param target The compensation target specifying the compensation context
     * @return A Mono that completes when the compensated event is sent
     *
     * @see DomainEventStream.withCompensation
     * @see DomainEventBus.send
     * @see CompensationTarget
     */
    override fun compensate(
        eventStream: DomainEventStream,
        target: CompensationTarget
    ): Mono<Void> {
        eventStream.withCompensation(target)
        return eventBus.send(eventStream)
    }

    /**
     * Resends a range of events from the event store with compensation.
     *
     * This method loads events from the specified version range for an aggregate
     * and resends them as compensated events.
     *
     * @param aggregateId The aggregate ID to resend events for
     * @param headVersion The starting version (inclusive) of events to resend
     * @param tailVersion The ending version (inclusive) of events to resend
     * @param target The compensation target for the resend operation
     * @return A Mono containing the count of events that were resent
     *
     * @see EventStore.load
     * @see compensate
     * @see AggregateId
     * @see CompensationTarget
     */
    override fun resend(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
        target: CompensationTarget
    ): Mono<Long> =
        eventStore
            .load(
                aggregateId = aggregateId,
                headVersion = headVersion,
                tailVersion = tailVersion,
            ).concatMap {
                compensate(it, target).thenReturn(it)
            }.count()
}
