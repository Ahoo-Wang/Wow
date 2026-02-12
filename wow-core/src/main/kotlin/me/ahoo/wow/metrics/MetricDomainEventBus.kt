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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.metrics.Metrics.tagMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Metric decorator for domain event buses that collects metrics on domain event sending and receiving operations.
 * This class wraps any DomainEventBus implementation and adds metrics collection with tags for
 * aggregate name and source identification.
 *
 * @param T the specific type of DomainEventBus being decorated
 * @property delegate the underlying domain event bus implementation
 */
open class MetricDomainEventBus<T : DomainEventBus>(
    delegate: T
) : AbstractMetricDecorator<T>(delegate),
    DomainEventBus,
    Metrizable {
    /**
     * Sends a domain event stream and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param message the domain event stream to send
     * @return a Mono that completes when the event stream is sent
     */
    override fun send(message: DomainEventStream): Mono<Void> =
        delegate
            .send(message)
            .name(Wow.WOW_PREFIX + "event.send")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, message.aggregateName)
            .metrics()

    /**
     * Receives event stream exchanges for the specified named aggregates and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification and subscriber information.
     *
     * @param namedAggregates the set of named aggregates to receive events for
     * @return a Flux of event stream exchanges
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> =
        delegate
            .receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "event.receive")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()

    /**
     * Closes the domain event bus and releases any resources.
     * This delegates to the underlying domain event bus implementation.
     */
    override fun close() {
        delegate.close()
    }
}

/**
 * Metric decorator specifically for local domain event buses.
 * Extends MetricDomainEventBus to provide metrics collection for local domain event bus operations
 * while maintaining the LocalDomainEventBus interface.
 *
 * @property delegate the underlying local domain event bus implementation
 */
class MetricLocalDomainEventBus(
    delegate: LocalDomainEventBus
) : MetricDomainEventBus<LocalDomainEventBus>(delegate),
    LocalDomainEventBus {
    /**
     * Returns the number of subscribers for the specified named aggregate.
     * This delegates to the underlying local domain event bus implementation.
     *
     * @param namedAggregate the named aggregate to check subscriber count for
     * @return the number of subscribers
     */
    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

/**
 * Metric decorator specifically for distributed domain event buses.
 * Extends MetricDomainEventBus to provide metrics collection for distributed domain event bus operations
 * while maintaining the DistributedDomainEventBus interface.
 *
 * @property delegate the underlying distributed domain event bus implementation
 */
class MetricDistributedDomainEventBus(
    delegate: DistributedDomainEventBus
) : MetricDomainEventBus<DistributedDomainEventBus>(delegate),
    DistributedDomainEventBus
