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

import me.ahoo.wow.infra.accessor.function.FunctionAccessor
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

/**
 * Interface for accessing reactive functions that return Publisher-based types.
 * Extends FunctionAccessor to provide reactive stream support for functions
 * that return reactive types like Flux, Mono, or other Publisher implementations.
 *
 * @param T the type of the target object
 * @param R the reactive return type that extends Publisher
 */
interface ReactiveFunctionAccessor<T, out R : Publisher<*>> : FunctionAccessor<T, R>

/**
 * Interface for accessing functions that return Mono reactive streams.
 * Specializes ReactiveFunctionAccessor for Mono-specific operations,
 * providing type safety for single-value reactive returns.
 *
 * @param T the type of the target object
 * @param R the Mono return type
 */
interface MonoFunctionAccessor<T, out R : Mono<*>> : ReactiveFunctionAccessor<T, R>
