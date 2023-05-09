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
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class MetricEventStore(override val delegate: EventStore) : EventStore, Decorator<EventStore>, Metrizable {
    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return delegate.append(eventStream)
            .name(Wow.WOW_PREFIX + "eventstore.append")
            .tag(Metrics.AGGREGATE_KEY, eventStream.aggregateName)
            .metrics()
    }

    override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        return delegate.load(aggregateId, headVersion, tailVersion)
            .name(Wow.WOW_PREFIX + "eventstore.load")
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()
    }
}
