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
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Mono

/**
 * Metric decorator for domain event handlers that collects metrics on domain event processing operations.
 * This class wraps a DomainEventHandler and adds metrics collection with tags for aggregate name,
 * event name, and processor name to track event handling performance and success rates.
 *
 * @property delegate the underlying domain event handler implementation
 */
class MetricDomainEventHandler(
    override val delegate: DomainEventHandler
) : DomainEventHandler,
    Decorator<DomainEventHandler> {
    /**
     * Handles a domain event exchange and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate, event,
     * and processor identification.
     *
     * @param exchange the domain event exchange containing the event to handle
     * @return a Mono that completes when the event is handled
     * @throws IllegalArgumentException if the event function cannot be retrieved from the exchange
     */
    override fun handle(exchange: DomainEventExchange<*>): Mono<Void> =
        delegate
            .handle(exchange)
            .name(Wow.WOW_PREFIX + "event.handle")
            .tag(Metrics.AGGREGATE_KEY, exchange.message.aggregateName)
            .tag(Metrics.EVENT_KEY, exchange.message.name)
            .tag(Metrics.PROCESSOR_KEY, requireNotNull(exchange.getEventFunction()).processorName)
            .metrics()
}
