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
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.LocalHandoff
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.publisher.Mono

internal open class MetricStateEventBus<T : StateEventBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricMessageBus<StateEvent<*>, StateEventExchange<*>, T>(delegate, metrics, source, "state_event_bus"),
    StateEventBus

internal class MetricLocalStateEventBus(
    delegate: LocalStateEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricStateEventBus<LocalStateEventBus>(delegate, metrics, source),
    LocalStateEventBus {
    override fun handOff(message: StateEvent<*>): Mono<LocalHandoff> =
        meteredHandOff(message, delegate.handOff(message))

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedStateEventBus(
    delegate: DistributedStateEventBus,
    metrics: WowMetrics,
    source: String,
) : MetricStateEventBus<DistributedStateEventBus>(delegate, metrics, source),
    DistributedStateEventBus
