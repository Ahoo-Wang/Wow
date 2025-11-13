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

package me.ahoo.wow.metrics

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Metric decorator for snapshot repositories that collects metrics on snapshot storage and retrieval operations.
 * This class wraps a SnapshotRepository implementation and adds metrics collection with tags for
 * aggregate name and source identification to track snapshot repository performance.
 *
 * @property delegate the underlying snapshot repository implementation
 */
class MetricSnapshotRepository(
    delegate: SnapshotRepository
) : AbstractMetricDecorator<SnapshotRepository>(delegate),
    SnapshotRepository {
    /**
     * The name of the snapshot repository.
     * This delegates to the underlying snapshot repository implementation.
     */
    override val name: String
        get() = delegate.name

    /**
     * Loads a snapshot for the specified aggregate ID and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param S the type of the snapshot state
     * @param aggregateId the aggregate ID to load the snapshot for
     * @return a Mono containing the snapshot, or empty if no snapshot exists
     */
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> =
        delegate
            .load<S>(aggregateId)
            .name(Wow.WOW_PREFIX + "snapshot.load")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()

    /**
     * Gets the version of the latest snapshot for the specified aggregate ID and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification.
     *
     * @param aggregateId the aggregate ID to get the version for
     * @return a Mono containing the snapshot version, or empty if no snapshot exists
     */
    override fun getVersion(aggregateId: AggregateId): Mono<Int> =
        delegate
            .getVersion(aggregateId)
            .name(Wow.WOW_PREFIX + "snapshot.getVersion")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()

    /**
     * Saves a snapshot and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param S the type of the snapshot state
     * @param snapshot the snapshot to save
     * @return a Mono that completes when the snapshot is saved
     */
    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> =
        delegate
            .save(snapshot)
            .name(Wow.WOW_PREFIX + "snapshot.save")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, snapshot.aggregateId.aggregateName)
            .metrics()

    /**
     * Scans for aggregate IDs in the snapshot repository starting after the specified ID
     * and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification.
     *
     * @param namedAggregate the named aggregate to scan for
     * @param afterId the aggregate ID to start scanning after
     * @param limit the maximum number of aggregate IDs to return
     * @return a Flux of aggregate IDs
     */
    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> =
        delegate
            .scanAggregateId(namedAggregate, afterId, limit)
            .name(Wow.WOW_PREFIX + "snapshot.scanAggregateId")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
            .metrics()
}
