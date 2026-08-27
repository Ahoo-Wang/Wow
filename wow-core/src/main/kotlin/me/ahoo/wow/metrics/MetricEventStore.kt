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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class MetricEventStore(
    delegate: EventStore,
    metrics: WowMetrics,
    source: String,
) : MetricComponentDecorator<EventStore>(delegate, metrics, source),
    EventStore {
    override fun append(eventStream: DomainEventStream): Mono<Void> =
        metrics.operation(
            delegate.append(eventStream),
            descriptor("append", eventStream.contextName, eventStream.aggregateName),
        )

    override fun load(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
    ): Flux<DomainEventStream> =
        metrics.operation(
            delegate.load(aggregateId, headVersion, tailVersion),
            descriptor("load_by_version", aggregateId.contextName, aggregateId.aggregateName),
        )

    override fun load(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long,
    ): Flux<DomainEventStream> =
        metrics.operation(
            delegate.load(aggregateId, headEventTime, tailEventTime),
            descriptor("load_by_time", aggregateId.contextName, aggregateId.aggregateName),
        )

    override fun existsRequestId(
        aggregateId: AggregateId,
        requestId: String,
    ): Mono<Boolean> =
        metrics.operation(
            delegate.existsRequestId(aggregateId, requestId),
            descriptor("exists_request_id", aggregateId.contextName, aggregateId.aggregateName),
        )

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> =
        metrics.operation(
            delegate.last(aggregateId),
            descriptor("last", aggregateId.contextName, aggregateId.aggregateName),
        )

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int,
    ): Flux<AggregateId> =
        metrics.operation(
            delegate.scanAggregateId(namedAggregate, afterId, limit),
            descriptor("scan_aggregate_id", namedAggregate.contextName, namedAggregate.aggregateName),
        )

    private fun descriptor(
        operation: String,
        context: String,
        aggregate: String,
    ): MetricDescriptor = descriptor(
        component = "event_store",
        operation = operation,
        context = context,
        aggregate = aggregate,
    )

    override fun close() = delegate.close()
}
