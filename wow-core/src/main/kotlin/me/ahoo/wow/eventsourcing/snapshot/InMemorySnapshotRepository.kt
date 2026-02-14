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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.serialization.toObject
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of SnapshotRepository for testing and development.
 * Stores snapshots as JSON strings in a thread-safe map.
 */
class InMemorySnapshotRepository : SnapshotRepository {
    companion object {
        /**
         * The name of this repository.
         */
        const val NAME = "in_memory"
    }

    /**
     * The name of this repository.
     */
    override val name: String
        get() = NAME

    /**
     * Thread-safe storage for snapshots, keyed by aggregate ID.
     */
    private val aggregateIdMapSnapshot = ConcurrentHashMap<AggregateId, ObjectNode>()

    /**
     * Loads a snapshot from the in-memory map by deserializing the JSON string.
     *
     * @param aggregateId the ID of the aggregate
     * @return a Mono emitting the snapshot or empty if not found
     */
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
        Mono.fromCallable {
            aggregateIdMapSnapshot[aggregateId]?.toObject<Snapshot<S>>()
        }

    /**
     * Saves a snapshot to the in-memory map by serializing it to JSON.
     *
     * @param snapshot the snapshot to save
     * @return a Mono that completes when the save operation is done
     */
    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
        Mono.fromRunnable {
            aggregateIdMapSnapshot[snapshot.aggregateId] = snapshot.toJsonNode()
        }

    /**
     * Scans aggregate IDs from the in-memory map, sorted and filtered by afterId and limit.
     *
     * @param namedAggregate the named aggregate (not used in this implementation)
     * @param afterId the ID to start scanning after
     * @param limit the maximum number of IDs to return
     * @return a Flux of aggregate IDs
     */
    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> =
        aggregateIdMapSnapshot.keys
            .sortedBy { it.id }
            .toFlux()
            .filter {
                it.id > afterId
            }.take(limit.toLong())
}
