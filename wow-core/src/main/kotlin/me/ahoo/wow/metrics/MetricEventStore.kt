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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Metric decorator for event stores that collects metrics on event storage and retrieval operations.
 * This class wraps an EventStore implementation and adds metrics collection with tags for
 * aggregate name and source identification to track event store performance.
 *
 * @property delegate the underlying event store implementation
 */
class MetricEventStore(
    delegate: EventStore
) : AbstractMetricDecorator<EventStore>(delegate),
    EventStore,
    Metrizable {
    /**
     * Appends a domain event stream to the event store and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param eventStream the domain event stream to append
     * @return a Mono that completes when the event stream is appended
     */
    override fun append(eventStream: DomainEventStream): Mono<Void> =
        delegate
            .append(eventStream)
            .name(Wow.WOW_PREFIX + "eventstore.append")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, eventStream.aggregateName)
            .metrics()

    /**
     * Loads domain event streams for the specified aggregate ID within the given version range
     * and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification.
     *
     * @param aggregateId the aggregate ID to load events for
     * @param headVersion the starting version number (inclusive)
     * @param tailVersion the ending version number (inclusive)
     * @return a Flux of domain event streams
     */
    override fun load(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> =
        delegate
            .load(aggregateId, headVersion, tailVersion)
            .name(Wow.WOW_PREFIX + "eventstore.load")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()

    /**
     * Loads domain event streams for the specified aggregate ID within the given time range
     * and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification.
     *
     * @param aggregateId the aggregate ID to load events for
     * @param headEventTime the starting event time (inclusive) in milliseconds since epoch
     * @param tailEventTime the ending event time (inclusive) in milliseconds since epoch
     * @return a Flux of domain event streams
     */
    override fun load(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> =
        delegate
            .load(aggregateId, headEventTime, tailEventTime)
            .name(Wow.WOW_PREFIX + "eventstore.load")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()
}
