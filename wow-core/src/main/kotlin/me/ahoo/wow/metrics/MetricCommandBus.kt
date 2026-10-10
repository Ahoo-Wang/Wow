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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.LocalHandoff
import reactor.core.publisher.Mono

internal open class MetricCommandBus<T : CommandBus>(
    delegate: T,
    metrics: WowMetrics,
    source: String,
) : MetricMessageBus<CommandMessage<*>, ServerCommandExchange<*>, T>(delegate, metrics, source, "command_bus"),
    CommandBus {
    override fun messageName(message: CommandMessage<*>): String = message.name
}

internal class MetricLocalCommandBus(
    delegate: LocalCommandBus,
    metrics: WowMetrics,
    source: String,
) : MetricCommandBus<LocalCommandBus>(delegate, metrics, source),
    LocalCommandBus {
    override fun handOff(message: CommandMessage<*>): Mono<LocalHandoff> =
        meteredHandOff(message, delegate.handOff(message))

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

internal class MetricDistributedCommandBus(
    delegate: DistributedCommandBus,
    metrics: WowMetrics,
    source: String,
) : MetricCommandBus<DistributedCommandBus>(delegate, metrics, source),
    DistributedCommandBus
