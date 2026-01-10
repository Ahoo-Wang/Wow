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
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import kotlin.reflect.KFunction

/**
 * MonoFunctionAccessor for functions that return Publisher streams.
 * This accessor converts Publisher results to Mono<D> by taking the first emitted item,
 * providing compatibility with the Reactive Streams specification.
 *
 * @param T the type of the target object
 * @param D the type of data in the Publisher
 * @property function the Kotlin function that returns a Publisher
 */
class PublisherMonoFunctionAccessor<T, D>(
    function: KFunction<*>
) : AbstractMonoFunctionAccessor<T, Mono<D>>(function) {

    /**
     * Invokes the function that returns a Publisher and converts it to a Mono.
     * Uses Mono.defer for lazy evaluation and toMono() to convert the Publisher
     * to a Mono that emits the first item.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function
     * @return a Mono containing the first item emitted by the Publisher
     */
    override operator fun invoke(
        target: T,
        args: Array<Any?>
    ): Mono<D> =
        Mono.defer {
            @Suppress("UNCHECKED_CAST")
            FastInvoke.safeInvoke<Publisher<Any>>(method, target, args).toFlux().collectList() as Mono<D>
        }
}
