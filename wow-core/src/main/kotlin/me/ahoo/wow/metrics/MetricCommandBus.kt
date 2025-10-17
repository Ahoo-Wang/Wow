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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.metrics.Metrics.tagMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

open class MetricCommandBus<T : CommandBus>(delegate: T) :
    CommandBus,
    AbstractMetricDecorator<T>(delegate),
    Metrizable {

    override fun send(message: CommandMessage<*>): Mono<Void> {
        return delegate.send(message)
            .name(Wow.WOW_PREFIX + "command.send")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, message.aggregateName)
            .tag(Metrics.COMMAND_KEY, message.name)
            .metrics()
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return delegate.receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "command.receive")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()
    }

    override fun close() {
        delegate.close()
    }
}

class MetricLocalCommandBus(delegate: LocalCommandBus) :
    LocalCommandBus,
    MetricCommandBus<LocalCommandBus>(delegate) {
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        return delegate.subscriberCount(namedAggregate)
    }
}

class MetricDistributedCommandBus(delegate: DistributedCommandBus) :
    DistributedCommandBus,
    MetricCommandBus<DistributedCommandBus>(delegate)
