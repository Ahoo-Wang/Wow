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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.command.CommandHandler
import reactor.core.publisher.Mono

/**
 * Metric decorator for command handlers that collects metrics on command processing operations.
 * This class wraps a CommandHandler and adds metrics collection with tags for aggregate name
 * and command name to track command handling performance and success rates.
 *
 * @property delegate the underlying command handler implementation
 */
class MetricCommandHandler(
    override val delegate: CommandHandler
) : CommandHandler,
    Decorator<CommandHandler> {
    /**
     * Handles a server command exchange and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate and command identification.
     *
     * @param exchange the server command exchange containing the command to handle
     * @return a Mono that completes when the command is handled
     */
    override fun handle(exchange: ServerCommandExchange<*>): Mono<Void> =
        delegate
            .handle(exchange)
            .name(Wow.WOW_PREFIX + "command.handle")
            .tag(Metrics.AGGREGATE_KEY, exchange.message.aggregateName)
            .tag(Metrics.COMMAND_KEY, exchange.message.name)
            .metrics()
}
