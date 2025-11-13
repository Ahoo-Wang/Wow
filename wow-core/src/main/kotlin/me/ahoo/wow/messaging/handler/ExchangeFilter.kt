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

import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import reactor.core.publisher.Mono

/**
 * A filter for processing message exchanges in a filter chain.
 *
 * Exchange filters can intercept and modify message exchanges as they pass
 * through the processing pipeline, enabling cross-cutting concerns like
 * logging, authentication, and error handling.
 *
 * @param T The type of message exchange this filter handles
 */
fun interface ExchangeFilter<T : MessageExchange<*, *>> : Filter<T> {
    /**
     * Processes the message exchange and optionally calls the next filter in the chain.
     *
     * @param exchange The message exchange to process
     * @param next The next filter in the chain
     * @return A Mono that completes when filtering is done
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun filter(
        exchange: T,
        next: FilterChain<T>
    ): Mono<Void>
}
