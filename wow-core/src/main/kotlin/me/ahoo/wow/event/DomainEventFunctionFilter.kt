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

package me.ahoo.wow.event

import me.ahoo.wow.api.annotation.ORDER_DEFAULT
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.ExchangeFilter
import reactor.core.publisher.Mono

@FilterType(DomainEventDispatcher::class)
@Order(ORDER_DEFAULT)
open class DomainEventFunctionFilter(
    private val serviceProvider: ServiceProvider
) :
    ExchangeFilter<DomainEventExchange<*>> {

    override fun filter(exchange: DomainEventExchange<*>, next: FilterChain<DomainEventExchange<*>>): Mono<Void> {
        exchange.setServiceProvider(serviceProvider)
        val eventFunction = checkNotNull(exchange.getEventFunction())
        return eventFunction
            .invoke(exchange)
            .checkpoint("Invoke ${eventFunction.fullyQualifiedName} [DomainEventFunctionFilter]")
            .then(next.filter(exchange))
    }
}
