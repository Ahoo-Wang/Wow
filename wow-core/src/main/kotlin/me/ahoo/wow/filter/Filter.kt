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
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * Annotation to mark the type of filter.
 *
 * This annotation can be applied to other annotation classes or classes and is inherited.
 *
 * @param value one or more [KClass] type parameters indicating the types handled by the filter
 */
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Inherited
annotation class FilterType(
    vararg val value: KClass<*>
)

/**
 * Functional interface for implementing custom filter logic.
 *
 * Uses generics to allow filtering with different types in various contexts.
 *
 * @param T the context type processed by the filter
 */
fun interface Filter<T> {
    /**
     * Implements the filter logic.
     *
     * This method receives a context object and a filter chain. After executing the filter logic,
     * it must call the filter chain's next method to continue executing the next filter in the chain.
     *
     * @param context the current context object for the filter operation
     * @param next the filter chain object to call the next filter in the chain
     * @return a [Mono] of [Void] indicating the completion of the filter operation
     */
    fun filter(
        context: T,
        next: FilterChain<T>
    ): Mono<Void>
}
