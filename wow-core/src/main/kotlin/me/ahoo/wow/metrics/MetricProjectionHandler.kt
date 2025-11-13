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
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.projection.ProjectionHandler
import reactor.core.publisher.Mono

/**
 * Metric decorator for projection handlers that collects metrics on projection processing operations.
 * This class wraps a ProjectionHandler and adds metrics collection with tags for aggregate name,
 * event name, and processor name to track projection performance and success rates.
 *
 * @property delegate the underlying projection handler implementation
 */
class MetricProjectionHandler(
    override val delegate: ProjectionHandler
) : ProjectionHandler,
    Decorator<ProjectionHandler> {
    /**
     * Handles a domain event exchange for projection and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate, event,
     * and processor identification.
     *
     * @param exchange the domain event exchange containing the event to project
     * @return a Mono that completes when the projection is handled
     * @throws IllegalArgumentException if the event function cannot be retrieved from the exchange
     */
    override fun handle(exchange: DomainEventExchange<*>): Mono<Void> =
        delegate
            .handle(exchange)
            .name(Wow.WOW_PREFIX + "projection.handle")
            .tag(Metrics.AGGREGATE_KEY, exchange.message.aggregateName)
            .tag(Metrics.EVENT_KEY, exchange.message.name)
            .tag(Metrics.PROCESSOR_KEY, requireNotNull(exchange.getEventFunction()).processorName)
            .metrics()
}
