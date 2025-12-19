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
import me.ahoo.wow.messaging.function.logErrorResume
import me.ahoo.wow.modeling.command.CommandFilter
import me.ahoo.wow.modeling.command.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.command.getCommandAggregate
import reactor.core.publisher.Mono

/**
 * Filter that sends state events to the state event bus after command processing.
 * This filter runs after domain events are sent, ensuring that subscribers receive
 * both the domain event and the updated aggregate state.
 *
 * The filter creates a state event by combining the domain event stream with the current aggregate state,
 * then publishes it to the configured state event bus.
 */
@Order(ORDER_LAST, after = [SendDomainEventStreamFilter::class])
class SendStateEventFilter(
    private val stateEventBus: StateEventBus
) : CommandFilter {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    /**
     * Filters the command exchange by sending a state event if applicable.
     * Creates and sends a state event containing both the domain event and aggregate state,
     * then continues the filter chain.
     *
     * @param exchange The server command exchange containing the command and resulting events.
     * @param next The next filter in the chain.
     * @return A Mono that completes when filtering is done.
     */
    override fun filter(
        exchange: ServerCommandExchange<*>,
        next: FilterChain<ServerCommandExchange<*>>
    ): Mono<Void> {
        return Mono.defer {
            val eventStream = exchange.getEventStream()
            if (eventStream == null) {
                log.warn { "No event stream." }
                return@defer next.filter(exchange)
            }
            val state = exchange.getCommandAggregate<Any, Any>()?.state
            if (state == null) {
                log.warn { "No state." }
                return@defer next.filter(exchange)
            }
            if (!state.initialized) {
                return@defer next.filter(exchange)
            }
            val stateEvent = eventStream.copy().toStateEvent(state)
            stateEventBus.send(stateEvent)
                .checkpoint("Send Message[${eventStream.id}] [SendStateEventFilter]")
                .logErrorResume()
                .then(next.filter(exchange))
        }
    }
}
