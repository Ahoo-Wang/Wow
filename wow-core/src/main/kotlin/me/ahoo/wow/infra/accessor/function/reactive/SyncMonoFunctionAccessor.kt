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

import me.ahoo.wow.infra.accessor.method.FastInvoke
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction

/**
 * MonoFunctionAccessor for synchronous functions that don't return reactive types.
 * This accessor wraps synchronous function calls in Mono.fromCallable to provide
 * reactive stream compatibility while executing the function on a blocking thread.
 *
 * @param T the type of the target object
 * @param D the type of data in the Mono
 * @property function the synchronous Kotlin function to be invoked
 */
class SyncMonoFunctionAccessor<T, D : Any>(
    function: KFunction<*>
) : AbstractMonoFunctionAccessor<T, Mono<D>>(function) {

    /**
     * Invokes the synchronous function and wraps the result in a Mono.
     * Uses Mono.fromCallable to execute the blocking operation on a suitable thread
     * and provide reactive stream compatibility.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function
     * @return a Mono containing the result of the synchronous function call
     */
    override operator fun invoke(
        target: T,
        args: Array<Any?>
    ): Mono<D> =
        Mono.fromCallable {
            FastInvoke.safeInvoke(method, target, args)
        }
}
