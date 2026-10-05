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
package me.ahoo.wow.infra.accessor.function.reactive

import reactor.core.publisher.Mono
import kotlin.reflect.KFunction

/**
 * Creates the [MonoFunctionAccessor] of a message function: one accessor type for every return shape, adapting the
 * result with the [ResultAdapter] chosen for the function's declaration.
 */
object MonoMethodAccessorFactory {
    /**
     * Creates the accessor of [function]: a `suspend` function, or one returning `Flow`, `Mono`, `Flux`, another
     * `Publisher` or a plain value, each adapted to a `Mono`; a function annotated with `@Blocking` is moved off
     * non-blocking threads.
     *
     * @param T the type of the target object
     * @param D the type of data in the Mono
     * @param function the Kotlin function to create an accessor for
     */
    fun <T, D : Any> create(function: KFunction<*>): MonoFunctionAccessor<T, Mono<D>> =
        AdaptedMonoFunctionAccessor(function)
}

fun <T, D : Any> KFunction<*>.toMonoFunctionAccessor(): MonoFunctionAccessor<T, Mono<D>> =
    MonoMethodAccessorFactory.create(
        this,
    )
