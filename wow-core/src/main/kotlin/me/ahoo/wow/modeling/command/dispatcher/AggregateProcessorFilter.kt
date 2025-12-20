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

package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.wow.api.annotation.ORDER_DEFAULT
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import reactor.core.publisher.Mono

@Order(ORDER_DEFAULT)
class AggregateProcessorFilter(
    private val serviceProvider: ServiceProvider,
) : CommandFilter {
    override fun filter(
        exchange: ServerCommandExchange<*>,
        next: FilterChain<ServerCommandExchange<*>>
    ): Mono<Void> {
        exchange.setServiceProvider(serviceProvider)
        val aggregateProcessor = checkNotNull(exchange.getAggregateProcessor())
        return aggregateProcessor
            .process(exchange)
            .checkpoint(
                "[${aggregateProcessor.aggregateId}] Process Command[${exchange.message.id}] [AggregateProcessorFilter]"
            )
            .finallyAck(exchange)
            .then(next.filter(exchange))
    }
}
