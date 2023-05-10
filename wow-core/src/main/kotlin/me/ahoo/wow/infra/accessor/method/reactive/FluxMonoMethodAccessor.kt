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

import me.ahoo.wow.infra.accessor.ensureAccessible
import me.ahoo.wow.infra.accessor.method.MethodAccessor.Companion.invoke
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method

data class FluxMonoMethodAccessor<T, D>(override val method: Method) : MonoMethodAccessor<T, Mono<List<D>>> {

    init {
        method.ensureAccessible()
    }

    override operator fun invoke(target: T, vararg args: Any?): Mono<List<D>> {
        return Mono.defer {
            invoke<T, Flux<D>>(method, target, *args).collectList()
        }
    }
}
