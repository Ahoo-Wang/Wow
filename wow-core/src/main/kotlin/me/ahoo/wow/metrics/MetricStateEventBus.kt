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

open class MetricStateEventBus<T : StateEventBus>(delegate: T) :
    StateEventBus,
    AbstractMetricDecorator<T>(delegate),
    Metrizable {

    override fun send(message: StateEvent<*>): Mono<Void> {
        return delegate.send(message)
            .name(Wow.WOW_PREFIX + "state.send")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, message.aggregateName)
            .metrics()
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<StateEventExchange<*>> {
        return delegate.receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "state.receive")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()
    }

    override fun close() {
        delegate.close()
    }
}

class MetricLocalStateEventBus(delegate: LocalStateEventBus) :
    LocalStateEventBus,
    MetricStateEventBus<LocalStateEventBus>(delegate) {
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        return delegate.subscriberCount(namedAggregate)
    }
}

class MetricDistributedStateEventBus(delegate: DistributedStateEventBus) :
    DistributedStateEventBus,
    MetricStateEventBus<DistributedStateEventBus>(delegate)
