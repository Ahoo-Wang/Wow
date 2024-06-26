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

package me.ahoo.wow.filter

import reactor.core.publisher.Mono

/**
 * Used to orchestrate [Handler].
 *
 * like [org.springframework.web.server.handler.WebFilterChain]
 */
fun interface FilterChain<T> {
    fun filter(context: T): Mono<Void>
}

object EmptyFilterChain : FilterChain<Any> {
    override fun filter(context: Any): Mono<Void> {
        return Mono.empty()
    }

    fun <T> instance(): FilterChain<T> {
        @Suppress("UNCHECKED_CAST")
        return this as FilterChain<T>
    }
}

abstract class AbstractFilterChain<T>(
    val current: Filter<T>,
    val next: FilterChain<T>
) : FilterChain<T> {
    override fun filter(context: T): Mono<Void> {
        return current.filter(context, next)
    }
}

open class SimpleFilterChain<T>(
    current: Filter<T>,
    next: FilterChain<T>
) : AbstractFilterChain<T>(current, next)
