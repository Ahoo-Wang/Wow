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

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmErasure

/**
 * Factory object for creating MonoFunctionAccessor instances based on function return types.
 * Automatically determines the appropriate accessor implementation based on the function's
 * return type and any blocking annotations.
 */
object MonoMethodAccessorFactory {
    /**
     * Creates a MonoFunctionAccessor for the given function, automatically selecting
     * the appropriate implementation based on the function's return type.
     *
     * The factory chooses implementations as follows:
     * - Mono return type: SimpleMonoFunctionAccessor
     * - Flux return type: FluxMonoFunctionAccessor (collects to List)
     * - Publisher return type: PublisherMonoFunctionAccessor
     * - Synchronous return type: SyncMonoFunctionAccessor
     *
     * If the function is annotated with @Blocking, it wraps the accessor with BlockingMonoFunctionAccessor.
     *
     * @param T the type of the target object
     * @param D the type of data in the Mono
     * @param function the Kotlin function to create an accessor for
     * @return a MonoFunctionAccessor appropriate for the function's return type
     */
    fun <T, D : Any> create(function: KFunction<*>): MonoFunctionAccessor<T, Mono<D>> {
        val returnType = function.returnType.jvmErasure

        val monoMethodAccessor: MonoFunctionAccessor<T, Mono<D>> = when {
            function.isSuspend -> {
                SuspendMonoFunctionAccessor(function)
            }

            returnType.isSubclassOf(Flow::class) -> {
                @Suppress("UNCHECKED_CAST")
                FlowMonoFunctionAccessor<T, Any>(function) as MonoFunctionAccessor<T, Mono<D>>
            }

            returnType.isSubclassOf(Mono::class) -> {
                SimpleMonoFunctionAccessor(function)
            }

            returnType.isSubclassOf(Flux::class) -> {
                @Suppress("UNCHECKED_CAST")
                FluxMonoFunctionAccessor<T, Any>(function) as MonoFunctionAccessor<T, Mono<D>>
            }

            returnType.isSubclassOf(Publisher::class) -> {
                PublisherMonoFunctionAccessor(function)
            }

            else -> {
                SyncMonoFunctionAccessor(function)
            }
        }

        return function.scanAnnotation<Blocking>()?.let {
            BlockingMonoFunctionAccessor(monoMethodAccessor)
        } ?: monoMethodAccessor
    }
}

/**
 * Extension function that converts a Kotlin function to a MonoFunctionAccessor.
 * Provides a convenient way to create accessors for functions that should return Mono streams.
 *
 * @param T the type of the target object
 * @param D the type of data in the Mono
 * @return a MonoFunctionAccessor for this function
 */
fun <T, D : Any> KFunction<*>.toMonoFunctionAccessor(): MonoFunctionAccessor<T, Mono<D>> =
    MonoMethodAccessorFactory.create(
        this,
    )
