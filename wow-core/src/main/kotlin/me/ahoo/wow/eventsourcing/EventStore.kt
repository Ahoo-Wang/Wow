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

/**
 * Interface for storing and retrieving domain event streams.
 * Provides methods to append events and load event streams by aggregate ID and version/time ranges.
 * @author ahoo wang
 */
interface EventStore {
    /**
     * Appends a domain event stream to the event store.
     * Ensures transaction consistency and handles version conflicts.
     *
     * @param eventStream the domain event stream to append
     * @return a Mono that completes when the append operation is successful
     * @throws EventVersionConflictException if there's a version conflict
     * @throws DuplicateAggregateIdException if the aggregate ID already exists
     * @throws DuplicateRequestIdException if the request ID is duplicate
     */
    @Throws(
        EventVersionConflictException::class,
        DuplicateAggregateIdException::class,
        DuplicateRequestIdException::class,
    )
    fun append(eventStream: DomainEventStream): Mono<Void>

    /**
     * Loads domain event streams for the specified aggregate within the given version range.
     * The range is inclusive: [headVersion, tailVersion].
     *
     * @param aggregateId the ID of the aggregate to load events for
     * @param headVersion the starting version (inclusive, default: 1)
     * @param tailVersion the ending version (inclusive, default: Int.MAX_VALUE - 1)
     * @return a Flux of domain event streams
     */
    fun load(
        aggregateId: AggregateId,
        headVersion: Int = DEFAULT_HEAD_VERSION,
        tailVersion: Int = DEFAULT_TAIL_VERSION
    ): Flux<DomainEventStream>

    /**
     *  Loads a single domain event stream for the specified aggregate at the given version.
     */
    fun single(aggregateId: AggregateId, version: Int): Mono<DomainEventStream> {
        return load(aggregateId, version, version).next()
    }

    /**
     * Loads domain event streams for the specified aggregate within the given event time range.
     * The range is inclusive: [headEventTime, tailEventTime].
     *
     * @param aggregateId the ID of the aggregate to load events for
     * @param headEventTime the starting event time (inclusive)
     * @param tailEventTime the ending event time (inclusive)
     * @return a Flux of domain event streams
     */
    fun load(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream>

    companion object {
        /**
         * Default starting version for loading events (inclusive).
         */
        const val DEFAULT_HEAD_VERSION: Int = 1

        /**
         * Default ending version for loading events (inclusive).
         */
        const val DEFAULT_TAIL_VERSION: Int = Int.MAX_VALUE - 1
    }
}
