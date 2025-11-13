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
 * Interface for orchestrating a chain of filters to process a context.
 *
 * Similar to [org.springframework.web.server.handler.WebFilterChain], this interface allows
 * sequential execution of filters on a given context.
 *
 * @param T the type of context being processed by the filter chain
 */
fun interface FilterChain<T> {
    /**
     * Executes the filter chain on the given context.
     *
     * @param context the context to be processed by the filter chain
     * @return a [Mono] of [Void] that completes when the entire chain has finished processing
     */
    fun filter(context: T): Mono<Void>
}

/**
 * An empty filter chain that performs no operations.
 *
 * This implementation returns an empty [Mono], effectively doing nothing.
 * It serves as the terminal element in a filter chain.
 */
object EmptyFilterChain : FilterChain<Any> {
    /**
     * Returns an empty [Mono], indicating no processing is done.
     *
     * @param context the context (ignored in this implementation)
     * @return an empty [Mono] of [Void]
     */
    override fun filter(context: Any): Mono<Void> {
        return Mono.empty()
    }

    /**
     * Creates a type-safe instance of the empty filter chain.
     *
     * @param T the desired context type
     * @return a [FilterChain] of type [T] that performs no operations
     */
    fun <T> instance(): FilterChain<T> {
        @Suppress("UNCHECKED_CAST")
        return this as FilterChain<T>
    }
}


/**
 * Abstract base class for filter chains that delegates to a current filter and a next chain.
 *
 * This class implements the [FilterChain] interface by calling the current filter's [Filter.filter]
 * method with the context and the next chain.
 *
 * @param T the type of context being processed
 * @property current the current [Filter] to execute
 * @property next the next [FilterChain] in the sequence
 */
abstract class AbstractFilterChain<T>(
    val current: Filter<T>,
    val next: FilterChain<T>
) : FilterChain<T> {
    /**
     * Executes the current filter and delegates to the next chain.
     *
     * @param context the context to be processed
     * @return a [Mono] of [Void] that completes when the filter and subsequent chain finish
     */
    override fun filter(context: T): Mono<Void> {
        return current.filter(context, next)
    }
}

/**
 * A simple implementation of [AbstractFilterChain].
 *
 * This class provides a basic filter chain that executes filters in sequence.
 * It can be extended for custom behavior if needed.
 *
 * @param T the type of context being processed
 * @param current the current [Filter] to execute
 * @param next the next [FilterChain] in the sequence
 */
open class SimpleFilterChain<T>(
    current: Filter<T>,
    next: FilterChain<T>
) : AbstractFilterChain<T>(current, next)
