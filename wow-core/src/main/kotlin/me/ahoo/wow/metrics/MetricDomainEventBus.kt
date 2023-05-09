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
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.metrics.Metrics.tagMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class MetricDomainEventBus(override val delegate: DomainEventBus) :
    DomainEventBus,
    Decorator<DomainEventBus>,
    Metrizable {
    override fun send(eventStream: DomainEventStream): Mono<Void> {
        return delegate.send(eventStream)
            .name(Wow.WOW_PREFIX + "event.send")
            .tag("aggregate", eventStream.aggregateName)
            .metrics()
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return delegate.receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "event.receive")
            .tag("aggregate", namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()
    }
}
