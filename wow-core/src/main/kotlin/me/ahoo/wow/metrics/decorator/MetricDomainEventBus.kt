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

package me.ahoo.wow.metrics.decorator

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.messaging.LocalHandoff
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Mono

internal open class MetricDomainEventBus<T : DomainEventBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricMessageBus<DomainEventStream, EventStreamExchange, T>(delegate, metrics, source, "domain_event_bus"),
    DomainEventBus

internal class MetricLocalDomainEventBus(
    delegate: LocalDomainEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricDomainEventBus<LocalDomainEventBus>(delegate, metrics, source),
    LocalDomainEventBus {
    override fun handOff(message: DomainEventStream): Mono<LocalHandoff> =
        meteredHandOff(message, delegate.handOff(message))

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedDomainEventBus(
    delegate: DistributedDomainEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricDomainEventBus<DistributedDomainEventBus>(delegate, metrics, source),
    DistributedDomainEventBus
