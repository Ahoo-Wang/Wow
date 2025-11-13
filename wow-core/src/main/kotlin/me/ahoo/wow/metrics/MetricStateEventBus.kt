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
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.metrics.Metrics.tagMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Metric decorator for state event buses that collects metrics on state event sending and receiving operations.
 * This class wraps any StateEventBus implementation and adds metrics collection with tags for
 * aggregate name and source identification.
 *
 * @param T the specific type of StateEventBus being decorated
 * @property delegate the underlying state event bus implementation
 */
open class MetricStateEventBus<T : StateEventBus>(
    delegate: T
) : AbstractMetricDecorator<T>(delegate),
    StateEventBus,
    Metrizable {
    /**
     * Sends a state event and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param message the state event to send
     * @return a Mono that completes when the state event is sent
     */
    override fun send(message: StateEvent<*>): Mono<Void> =
        delegate
            .send(message)
            .name(Wow.WOW_PREFIX + "state.send")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, message.aggregateName)
            .metrics()

    /**
     * Receives state event exchanges for the specified named aggregates and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification and subscriber information.
     *
     * @param namedAggregates the set of named aggregates to receive state events for
     * @return a Flux of state event exchanges
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<StateEventExchange<*>> =
        delegate
            .receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "state.receive")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()

    /**
     * Closes the state event bus and releases any resources.
     * This delegates to the underlying state event bus implementation.
     */
    override fun close() {
        delegate.close()
    }
}

/**
 * Metric decorator specifically for local state event buses.
 * Extends MetricStateEventBus to provide metrics collection for local state event bus operations
 * while maintaining the LocalStateEventBus interface.
 *
 * @property delegate the underlying local state event bus implementation
 */
class MetricLocalStateEventBus(
    delegate: LocalStateEventBus
) : MetricStateEventBus<LocalStateEventBus>(delegate),
    LocalStateEventBus {
    /**
     * Returns the number of subscribers for the specified named aggregate.
     * This delegates to the underlying local state event bus implementation.
     *
     * @param namedAggregate the named aggregate to check subscriber count for
     * @return the number of subscribers
     */
    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

/**
 * Metric decorator specifically for distributed state event buses.
 * Extends MetricStateEventBus to provide metrics collection for distributed state event bus operations
 * while maintaining the DistributedStateEventBus interface.
 *
 * @property delegate the underlying distributed state event bus implementation
 */
class MetricDistributedStateEventBus(
    delegate: DistributedStateEventBus
) : MetricStateEventBus<DistributedStateEventBus>(delegate),
    DistributedStateEventBus
