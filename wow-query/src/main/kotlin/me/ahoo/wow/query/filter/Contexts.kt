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

package me.ahoo.wow.query.filter

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

@Deprecated("Scheduled for removal in 10.0.0. Use me.ahoo.wow.webflux.route raw-request extensions.")
object Contexts {
    private const val RAW_REQUEST_KEY = "__RAW_REQUEST___"

    fun <T : Any> Mono<T>.writeRawRequest(request: Any): Mono<T> =
        contextWrite { it.put(RAW_REQUEST_KEY, request) }

    fun <T : Any> Flux<T>.writeRawRequest(request: Any): Flux<T> =
        contextWrite { it.put(RAW_REQUEST_KEY, request) }

    fun <R> ContextView.getRawRequest(): R? = getOrDefault(RAW_REQUEST_KEY, null)
}
