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
package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory implementation of EventStore for testing and development purposes.
 * Stores event streams in memory using thread-safe collections.
 *
 * @author ahoo wang
 */
class InMemoryEventStore : AbstractEventStore() {
    /**
     * Thread-safe storage for event streams, keyed by aggregate ID.
     */
    private val events = ConcurrentHashMap<AggregateId, CopyOnWriteArrayList<DomainEventStream>>()

    /**
     * Appends an event stream to the in-memory storage.
     * Validates version conflicts and duplicate request IDs.
     *
     * @param eventStream the domain event stream to append
     * @return a Mono that completes when the append operation is done
     * @throws EventVersionConflictException if the version conflicts with existing events
     * @throws DuplicateRequestIdException if the request ID is already used
     */
    public override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        return Mono.fromRunnable {
            events.compute(
                eventStream.aggregateId,
            ) { _, value ->
                val aggregateStream = value ?: CopyOnWriteArrayList()
                val storedTailVersion =
                    if (aggregateStream.isEmpty()) 0 else aggregateStream.last().version
                if (eventStream.version <= storedTailVersion) {
                    throw EventVersionConflictException(
                        eventStream,
                    )
                }
                val requestRepeated =
                    aggregateStream.any {
                        it.requestId == eventStream.requestId
                    }
                if (requestRepeated) {
                    throw DuplicateRequestIdException(
                        eventStream.aggregateId,
                        eventStream.requestId,
                    )
                }
                aggregateStream.add(eventStream)
                return@compute aggregateStream
            }
        }
    }

    /**
     * Loads event streams for the specified aggregate within the version range.
     * Returns copies of the stored event streams.
     *
     * @param aggregateId the ID of the aggregate
     * @param headVersion the starting version (inclusive)
     * @param tailVersion the ending version (inclusive)
     * @return a Flux of domain event streams
     */
    public override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        return Flux.defer {
            val eventsOfAgg: CopyOnWriteArrayList<DomainEventStream> = events[aggregateId] ?: return@defer Flux.empty()
            eventsOfAgg
                .filter { it.version in headVersion..tailVersion }
                .map { it.copy() }
                .toFlux()
        }
    }

    /**
     * Loads event streams for the specified aggregate within the event time range.
     * Returns copies of the stored event streams.
     *
     * @param aggregateId the ID of the aggregate
     * @param headEventTime the starting event time (inclusive)
     * @param tailEventTime the ending event time (inclusive)
     * @return a Flux of domain event streams
     */
    public override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        return Flux.defer {
            val eventsOfAgg: CopyOnWriteArrayList<DomainEventStream> = events[aggregateId] ?: return@defer Flux.empty()
            eventsOfAgg
                .filter { it.createTime in headEventTime..tailEventTime }
                .map { it.copy() }
                .toFlux()
        }
    }
}
