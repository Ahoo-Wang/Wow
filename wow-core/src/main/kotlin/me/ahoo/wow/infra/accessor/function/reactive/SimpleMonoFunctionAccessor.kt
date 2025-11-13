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
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction

/**
 * Simple implementation of MonoFunctionAccessor for functions that already return Mono.
 * This accessor defers the execution of the underlying function to ensure proper
 * reactive stream behavior and lazy evaluation.
 *
 * @param T the type of the target object
 * @param D the type of data in the Mono
 * @property function the Kotlin function that returns a Mono
 */
data class SimpleMonoFunctionAccessor<T, D>(
    override val function: KFunction<*>
) : MonoFunctionAccessor<T, Mono<D>> {
    /**
     * Initialization block that ensures the underlying method is accessible for reflection.
     * This automatically makes private, protected, or package-private methods accessible.
     */
    init {
        method.ensureAccessible()
    }

    /**
     * Invokes the function and returns its Mono result, wrapped in Mono.defer for proper lazy evaluation.
     * The defer operator ensures that the function is only executed when the Mono is subscribed to.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function
     * @return a Mono containing the function result
     */
    override fun invoke(
        target: T,
        args: Array<Any?>
    ): Mono<D> =
        Mono.defer {
            super.invoke(target, args)
        }
}
