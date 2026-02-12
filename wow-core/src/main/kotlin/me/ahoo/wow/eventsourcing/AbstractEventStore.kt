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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base implementation of EventStore that provides common functionality for event storage and retrieval.
 * This class handles logging, validation, and error mapping for event stream operations.
 */
abstract class AbstractEventStore : EventStore {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Appends a domain event stream to the event store.
     * Logs the operation and maps version conflicts to appropriate exceptions.
     *
     * @param eventStream the domain event stream to append
     * @return a Mono that completes when the append operation is done
     * @throws DuplicateAggregateIdException if the aggregate ID already exists (for initial version)
     * @throws EventVersionConflictException if there's a version conflict
     */
    override fun append(eventStream: DomainEventStream): Mono<Void> {
        log.debug {
            "Append ${eventStream.aggregateId} - version[${eventStream.version}]"
        }
        return appendStream(eventStream)
            .onErrorMap(EventVersionConflictException::class.java) {
                if (it.eventStream.version == Version.INITIAL_VERSION) {
                    DuplicateAggregateIdException(it.eventStream, cause = it)
                } else {
                    it
                }
            }
    }

    /**
     * Abstract method to append the event stream to the underlying storage.
     * Implementations should handle the actual persistence logic.
     *
     * @param eventStream the domain event stream to append
     * @return a Mono that completes when the append operation is done
     */
    protected abstract fun appendStream(eventStream: DomainEventStream): Mono<Void>

    /**
     * Loads domain event streams for the specified aggregate within the given version range.
     * Validates that headVersion is non-negative and tailVersion is greater than or equal to headVersion.
     *
     * @param aggregateId the ID of the aggregate to load events for
     * @param headVersion the starting version (inclusive, must be >= 0)
     * @param tailVersion the ending version (inclusive, must be >= headVersion)
     * @return a Flux of domain event streams
     * @throws IllegalArgumentException if headVersion < 0 or tailVersion < headVersion
     */
    override fun load(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        log.debug {
            "Load $aggregateId - headVersion[$headVersion] - tailVersion[$tailVersion]."
        }
        require(headVersion > -1) {
            "$aggregateId headVersion[$headVersion] must be greater than -1!"
        }
        require(tailVersion >= headVersion) {
            "$aggregateId headEventTime[$tailVersion] must be greater than or equal to headEventTime[$headVersion]!"
        }
        return loadStream(aggregateId, headVersion, tailVersion)
    }

    /**
     * Loads domain event streams for the specified aggregate within the given event time range.
     * Validates that tailEventTime is greater than or equal to headEventTime.
     *
     * @param aggregateId the ID of the aggregate to load events for
     * @param headEventTime the starting event time (inclusive)
     * @param tailEventTime the ending event time (inclusive, must be >= headEventTime)
     * @return a Flux of domain event streams
     * @throws IllegalArgumentException if tailEventTime < headEventTime
     */
    override fun load(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        require(tailEventTime >= headEventTime) {
            "$aggregateId headEventTime[$headEventTime] must be greater than or equal to headEventTime[$headEventTime]!"
        }
        return loadStream(aggregateId, headEventTime, tailEventTime)
    }

    /**
     * Abstract method to load event streams by version range from the underlying storage.
     * Implementations should handle the actual retrieval logic.
     *
     * @param aggregateId the ID of the aggregate
     * @param headVersion the starting version
     * @param tailVersion the ending version
     * @return a Flux of domain event streams
     */
    protected abstract fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream>

    /**
     * Abstract method to load event streams by event time range from the underlying storage.
     * Implementations should handle the actual retrieval logic.
     *
     * @param aggregateId the ID of the aggregate
     * @param headEventTime the starting event time
     * @param tailEventTime the ending event time
     * @return a Flux of domain event streams
     */
    protected abstract fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream>
}
