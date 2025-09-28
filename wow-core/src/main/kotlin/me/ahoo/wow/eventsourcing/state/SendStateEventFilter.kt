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

package me.ahoo.wow.eventsourcing.state

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.function.logErrorResume
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.messaging.handler.retryStrategy
import me.ahoo.wow.modeling.command.CommandDispatcher
import me.ahoo.wow.modeling.command.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.command.getCommandAggregate
import reactor.core.publisher.Mono

@FilterType(CommandDispatcher::class)
@Order(ORDER_LAST, after = [SendDomainEventStreamFilter::class])
class SendStateEventFilter(private val stateEventBus: StateEventBus) : ExchangeFilter<ServerCommandExchange<*>> {
    companion object {
        private val log = KotlinLogging.logger { }
        private val retryStrategy = retryStrategy(logger = log)
    }

    override fun filter(
        exchange: ServerCommandExchange<*>,
        next: FilterChain<ServerCommandExchange<*>>
    ): Mono<Void> {
        return Mono.defer {
            val eventStream = exchange.getEventStream() ?: return@defer next.filter(exchange)
            val state = exchange.getCommandAggregate<Any, Any>()?.state ?: return@defer next.filter(exchange)
            if (!state.initialized) {
                return@defer next.filter(exchange)
            }
            val stateEvent = eventStream.copy().toStateEvent(state)
            stateEventBus.send(stateEvent)
                .checkpoint("Send Message[${eventStream.id}] [SendStateEventFilter]")
                .retryWhen(retryStrategy)
                .logErrorResume()
                .then(next.filter(exchange))
        }
    }
}
