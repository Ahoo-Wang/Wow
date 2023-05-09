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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.metrics.Metrics.tagMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class MetricCommandBus(override val delegate: CommandBus) : CommandBus, Decorator<CommandBus>, Metrizable {
    override fun <C : Any> send(command: CommandMessage<C>): Mono<Void> {
        return delegate.send(command)
            .name(Wow.WOW_PREFIX + "command.send")
            .tag(Metrics.AGGREGATE_KEY, command.aggregateName)
            .tag("command", command.name)
            .metrics()
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        return delegate.receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "command.receive")
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()
    }
}
