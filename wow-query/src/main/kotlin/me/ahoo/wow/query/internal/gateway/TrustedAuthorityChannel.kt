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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.query.gateway.QueryAuthority
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/** Per-runtime object capability used only by the framework-owned compatibility facade. */
internal class TrustedAuthorityChannel private constructor() {
    fun read(context: ContextView): QueryAuthority? = context.getOrDefault(this, null)

    fun <T : Any> bind(source: Mono<T>, authority: QueryAuthority): Mono<T> =
        source.contextWrite { context -> context.put(this, authority) }

    fun <T : Any> bind(source: Flux<T>, authority: QueryAuthority): Flux<T> =
        source.contextWrite { context -> context.put(this, authority) }

    companion object {
        fun create(): TrustedAuthorityChannel = TrustedAuthorityChannel()
    }
}
