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

package me.ahoo.wow.messaging.handler

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Utilities for acknowledging message exchanges.
 *
 * Provides extension functions to ensure messages are acknowledged
 * regardless of processing success or failure.
 */
object ExchangeAck {
    /**
     * Ensures the exchange is acknowledged after Mono completion, even on error.
     *
     * If the Mono fails, acknowledges first, then re-throws the error.
     * If successful, acknowledges after completion.
     *
     * @param exchange The exchange to acknowledge
     * @return A Mono that acknowledges the exchange
     */
    fun Mono<*>.finallyAck(exchange: MessageExchange<*, *>): Mono<Void> =
        onErrorResume {
            exchange.acknowledge()
                .then(Mono.error(it))
        }.then(exchange.acknowledge())

    /**
     * Ensures the exchange is acknowledged after Flux completion, even on error.
     *
     * If the Flux fails, acknowledges first, then re-throws the error.
     * If successful, acknowledges after completion.
     *
     * @param exchange The exchange to acknowledge
     * @return A Mono that acknowledges the exchange
     */
    fun Flux<*>.finallyAck(exchange: MessageExchange<*, *>): Mono<Void> =
        onErrorResume {
            exchange.acknowledge()
                .then(Mono.error(it))
        }.then(exchange.acknowledge())

    /**
     * Filters the flux and acknowledges exchanges that don't match the predicate.
     *
     * For each exchange, if it matches the predicate, it passes through.
     * If it doesn't match, it's acknowledged and filtered out.
     *
     * @param predicate The predicate to test exchanges against
     * @return A flux containing only exchanges that match the predicate
     */
    inline fun <T : MessageExchange<*, *>> Flux<T>.filterThenAck(crossinline predicate: (T) -> Boolean): Flux<T> =
        filterWhen {
            val matched = predicate(it)
            if (matched) {
                Mono.just(true)
            } else {
                it.acknowledge().thenReturn(false)
            }
        }
}
