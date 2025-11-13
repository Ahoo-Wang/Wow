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

import me.ahoo.wow.infra.accessor.ensureAccessible
import me.ahoo.wow.infra.accessor.method.FastInvoke
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction

/**
 * MonoFunctionAccessor for functions that return Flux streams.
 * This accessor converts Flux results to Mono<List<D>> by collecting all emitted items
 * into a list, providing a way to handle multi-value reactive streams in a Mono context.
 *
 * @param T the type of the target object
 * @param D the type of individual items in the Flux
 * @property function the Kotlin function that returns a Flux
 */
data class FluxMonoFunctionAccessor<T, D>(
    override val function: KFunction<*>
) : MonoFunctionAccessor<T, Mono<List<D>>> {
    /**
     * Initialization block that ensures the function is accessible for reflection.
     * This automatically makes private, protected, or package-private functions accessible.
     */
    init {
        function.ensureAccessible()
    }

    /**
     * Invokes the function that returns a Flux and collects all emitted items into a List.
     * Uses Mono.defer for lazy evaluation and collectList() to aggregate the Flux emissions.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function
     * @return a Mono containing a List of all items emitted by the Flux
     */
    override operator fun invoke(
        target: T,
        args: Array<Any?>
    ): Mono<List<D>> =
        Mono.defer {
            FastInvoke.safeInvoke<Flux<D>>(method, target, args).collectList()
        }
}
