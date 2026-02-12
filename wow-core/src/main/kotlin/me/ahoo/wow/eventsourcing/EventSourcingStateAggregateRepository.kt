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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import reactor.core.publisher.Mono

/**
 * Repository for loading state aggregates using event sourcing.
 * This repository reconstructs the current state of an aggregate by combining snapshots
 * (if available) with event streams from the event store. It supports loading aggregates
 * up to a specific version or event time, enabling point-in-time state reconstruction.
 *
 * The loading process works as follows:
 * 1. If loading the latest version (tailVersion = Int.MAX_VALUE), attempt to load from snapshot first.
 * 2. If no snapshot exists, create a new aggregate instance.
 * 3. Apply events from the event store starting from the aggregate's expected next version.
 *
 * @param stateAggregateFactory Factory for creating new state aggregate instances.
 * @param snapshotRepository Repository for loading and storing aggregate snapshots.
 * @param eventStore Store for retrieving event streams associated with aggregates.
 * @author ahoo wang
 */
class EventSourcingStateAggregateRepository(
    private val stateAggregateFactory: StateAggregateFactory,
    private val snapshotRepository: SnapshotRepository,
    private val eventStore: EventStore
) : StateAggregateRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Loads a state aggregate by its ID up to a specified version.
     *
     * This method reconstructs the aggregate state by:
     * - Loading from snapshot if tailVersion is Int.MAX_VALUE and a snapshot exists
     * - Creating a new aggregate instance otherwise
     * - Applying events from the event store up to the specified tailVersion
     *
     * @param S The type of the aggregate state.
     * @param aggregateId The unique identifier of the aggregate to load.
     * @param metadata Metadata describing the aggregate structure and behavior.
     * @param tailVersion The maximum version to load events up to. Use Int.MAX_VALUE for latest version.
     * @return A Mono emitting the reconstructed StateAggregate.
     * @throws IllegalArgumentException if aggregateId or metadata is invalid.
     * @throws RuntimeException if event sourcing fails due to event store errors.
     *
     * @sample
     * ```
     * val aggregateId = AggregateId("user", "123")
     * val metadata = StateAggregateMetadata<UserState>(...)
     * val aggregate = repository.load(aggregateId, metadata, Int.MAX_VALUE).block()
     * ```
     */

    override fun <S : Any> load(
        aggregateId: AggregateId,
        metadata: StateAggregateMetadata<S>,
        tailVersion: Int
    ): Mono<StateAggregate<S>> {
        log.debug {
            "Load $aggregateId version:$tailVersion."
        }
        val loadStateAggregate = if (tailVersion == Int.MAX_VALUE) {
            snapshotRepository.load<S>(aggregateId)
                .map {
                    it.toStateAggregate()
                }
                .defaultIfEmpty(stateAggregateFactory.create(metadata, aggregateId))
        } else {
            stateAggregateFactory.createAsMono(metadata, aggregateId)
        }

        return loadStateAggregate
            .flatMap { stateAggregate ->
                eventStore
                    .load(
                        aggregateId = aggregateId,
                        headVersion = stateAggregate.expectedNextVersion,
                        tailVersion = tailVersion
                    )
                    .map {
                        stateAggregate.onSourcing(it)
                    }
                    .then(Mono.just(stateAggregate))
            }
    }

    /**
     * Loads a state aggregate by its ID up to a specified event time.
     *
     * This method reconstructs the aggregate state by:
     * - Creating a new aggregate instance
     * - Applying events from the event store that occurred before or at the specified tailEventTime
     *
     * @param S The type of the aggregate state.
     * @param aggregateId The unique identifier of the aggregate to load.
     * @param metadata Metadata describing the aggregate structure and behavior.
     * @param tailEventTime The maximum event timestamp (in milliseconds since epoch) to load events up to.
     * @return A Mono emitting the reconstructed StateAggregate.
     * @throws IllegalArgumentException if aggregateId or metadata is invalid.
     * @throws RuntimeException if event sourcing fails due to event store errors.
     *
     * @sample
     * ```
     * val aggregateId = AggregateId("user", "123")
     * val metadata = StateAggregateMetadata<UserState>(...)
     * val eventTime = System.currentTimeMillis() - 86400000L // 1 day ago
     * val aggregate = repository.load(aggregateId, metadata, eventTime).block()
     * ```
     */
    override fun <S : Any> load(
        aggregateId: AggregateId,
        metadata: StateAggregateMetadata<S>,
        tailEventTime: Long
    ): Mono<StateAggregate<S>> {
        return stateAggregateFactory.createAsMono(metadata, aggregateId).flatMap { stateAggregate ->
            eventStore
                .load(
                    aggregateId = aggregateId,
                    headEventTime = stateAggregate.eventTime + 1,
                    tailEventTime = tailEventTime
                )
                .map {
                    stateAggregate.onSourcing(it)
                }
                .then(Mono.just(stateAggregate))
        }
    }
}
