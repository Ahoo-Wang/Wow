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

package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.messaging.function.logErrorResume
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterType
import reactor.core.publisher.Mono

@FilterType(CommandDispatcher::class)
@Order(ORDER_LAST, after = [AggregateProcessorFilter::class])
class SendDomainEventStreamFilter(private val domainEventBus: DomainEventBus) : Filter<ServerCommandExchange<*>> {
    override fun filter(
        exchange: ServerCommandExchange<*>,
        next: FilterChain<ServerCommandExchange<*>>
    ): Mono<Void> {
        return Mono.defer {
            val eventStream = exchange.eventStream ?: return@defer next.filter(exchange)
            domainEventBus.send(eventStream)
                .logErrorResume()
                .then(next.filter(exchange))
        }
    }
}
