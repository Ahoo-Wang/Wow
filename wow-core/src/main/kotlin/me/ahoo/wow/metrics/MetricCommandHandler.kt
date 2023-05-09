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

class MetricCommandHandler(override val delegate: CommandHandler) : CommandHandler, Decorator<CommandHandler> {
    override fun handle(exchange: ServerCommandExchange<Any>): Mono<Void> {
        return delegate.handle(exchange)
            .name(Wow.WOW_PREFIX + "command.handle")
            .tag(Metrics.AGGREGATE_KEY, exchange.message.aggregateName)
            .metrics()
    }
}
