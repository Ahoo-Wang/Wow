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

/**
 * Metric decorator for command buses that collects metrics on command sending and receiving operations.
 * This class wraps any CommandBus implementation and adds metrics collection with tags for
 * aggregate name, command name, and source identification.
 *
 * @param T the specific type of CommandBus being decorated
 * @property delegate the underlying command bus implementation
 */
open class MetricCommandBus<T : CommandBus>(
    delegate: T
) : AbstractMetricDecorator<T>(delegate),
    CommandBus,
    Metrizable {
    /**
     * Sends a command message and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate and command identification.
     *
     * @param message the command message to send
     * @return a Mono that completes when the command is sent
     */
    override fun send(message: CommandMessage<*>): Mono<Void> =
        delegate
            .send(message)
            .name(Wow.WOW_PREFIX + "command.send")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, message.aggregateName)
            .tag(Metrics.COMMAND_KEY, message.name)
            .metrics()

    /**
     * Receives command exchanges for the specified named aggregates and collects metrics on the operation.
     * Metrics collected include timing and tags for aggregate identification and subscriber information.
     *
     * @param namedAggregates the set of named aggregates to receive commands for
     * @return a Flux of server command exchanges
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> =
        delegate
            .receive(namedAggregates)
            .name(Wow.WOW_PREFIX + "command.receive")
            .tagSource()
            .tag(Metrics.AGGREGATE_KEY, namedAggregates.joinToString(",") { it.aggregateName })
            .tagMetricsSubscriber()

    /**
     * Closes the command bus and releases any resources.
     * This delegates to the underlying command bus implementation.
     */
    override fun close() {
        delegate.close()
    }
}

/**
 * Metric decorator specifically for local command buses.
 * Extends MetricCommandBus to provide metrics collection for local command bus operations
 * while maintaining the LocalCommandBus interface.
 *
 * @property delegate the underlying local command bus implementation
 */
class MetricLocalCommandBus(
    delegate: LocalCommandBus
) : MetricCommandBus<LocalCommandBus>(delegate),
    LocalCommandBus {
    /**
     * Returns the number of subscribers for the specified named aggregate.
     * This delegates to the underlying local command bus implementation.
     *
     * @param namedAggregate the named aggregate to check subscriber count for
     * @return the number of subscribers
     */
    override fun subscriberCount(namedAggregate: NamedAggregate): Int = delegate.subscriberCount(namedAggregate)
}

/**
 * Metric decorator specifically for distributed command buses.
 * Extends MetricCommandBus to provide metrics collection for distributed command bus operations
 * while maintaining the DistributedCommandBus interface.
 *
 * @property delegate the underlying distributed command bus implementation
 */
class MetricDistributedCommandBus(
    delegate: DistributedCommandBus
) : MetricCommandBus<DistributedCommandBus>(delegate),
    DistributedCommandBus
