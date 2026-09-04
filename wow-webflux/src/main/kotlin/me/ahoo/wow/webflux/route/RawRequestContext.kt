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

package me.ahoo.wow.webflux.route

import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView
import kotlin.jvm.optionals.getOrNull

private object RawRequestContextKey

fun ContextView.getRawRequest(): ServerRequest? =
    getOrEmpty<ServerRequest>(RawRequestContextKey).getOrNull()

fun <T : Any> Mono<T>.writeRawRequest(request: ServerRequest): Mono<T> =
    contextWrite { it.put(RawRequestContextKey, request) }

fun <T : Any> Flux<T>.writeRawRequest(request: ServerRequest): Flux<T> =
    contextWrite { it.put(RawRequestContextKey, request) }
