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
package me.ahoo.wow.infra.accessor.method.reactive

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method

/**
 * MonoMethodAccessorFactory .
 *
 * @author ahoo wang
 */
object MonoMethodAccessorFactory {
    fun <T, D> create(method: Method): MonoMethodAccessor<T, Mono<D>> {
        val returnType = method.returnType
        val monoMethodAccessor = if (Mono::class.java.isAssignableFrom(returnType)) {
            SimpleMonoMethodAccessor(method)
        } else if (Flux::class.java.isAssignableFrom(returnType)) {
            @Suppress("UNCHECKED_CAST")
            FluxMonoMethodAccessor<T, Any>(method) as MonoMethodAccessor<T, Mono<D>>
        } else if (Publisher::class.java.isAssignableFrom(returnType)) {
            PublisherMonoMethodAccessor(method)
        } else {
            SyncMonoMethodAccessor(method)
        }

        return method.scan<Blocking>()?.let {
            BlockingMonoMethodAccessor(monoMethodAccessor)
        } ?: monoMethodAccessor
    }
}

fun <T, D> Method.toMonoMethodAccessor(): MonoMethodAccessor<T, Mono<D>> {
    return MonoMethodAccessorFactory.create(this)
}
