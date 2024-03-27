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

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.infra.reflection.KAnnotationScanner.scanAnnotation
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmErasure

object MonoMethodAccessorFactory {
    fun <T, D> create(function: KFunction<*>): MonoFunctionAccessor<T, Mono<D>> {
        val returnType = function.returnType.jvmErasure
        val monoMethodAccessor = if (returnType.isSubclassOf(Mono::class)) {
            SimpleMonoFunctionAccessor(function)
        } else if (returnType.isSubclassOf(Flux::class)) {
            @Suppress("UNCHECKED_CAST")
            FluxMonoFunctionAccessor<T, Any>(function) as MonoFunctionAccessor<T, Mono<D>>
        } else if (returnType.isSubclassOf(Publisher::class)) {
            PublisherMonoFunctionAccessor(function)
        } else {
            SyncMonoFunctionAccessor(function)
        }

        return function.scanAnnotation<Blocking>()?.let {
            BlockingMonoFunctionAccessor(monoMethodAccessor)
        } ?: monoMethodAccessor
    }
}

fun <T, D> KFunction<*>.toMonoFunctionAccessor(): MonoFunctionAccessor<T, Mono<D>> {
    return MonoMethodAccessorFactory.create(this)
}
