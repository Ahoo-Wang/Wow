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

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.opentelemetry.ExchangeTraceMono
import reactor.core.publisher.Mono

@Order(ORDER_FIRST)
open class ConsumerTraceFilter<T : MessageExchange<*, *>>(private val consumerInstrumenter: Instrumenter<T, Unit>) :
    Filter<T> {
    override fun filter(
        exchange: T,
        next: FilterChain<T>
    ): Mono<Void> {
        val source = next.filter(exchange)
        val parentContext = Context.current()
        return ExchangeTraceMono(parentContext, consumerInstrumenter, exchange, source)
    }
}
