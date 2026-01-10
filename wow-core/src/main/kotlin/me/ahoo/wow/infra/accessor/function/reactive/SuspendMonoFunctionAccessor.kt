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

import kotlinx.coroutines.reactor.mono
import me.ahoo.wow.infra.accessor.ensureAccessible
import reactor.core.publisher.Mono
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

class SuspendMonoFunctionAccessor<T, D>(override val function: KFunction<*>) : MonoFunctionAccessor<T, Mono<D>> {
    init {
        function.ensureAccessible()
    }

    override operator fun invoke(target: T, args: Array<Any?>): Mono<D> {
        return mono {
            function.callSuspend(target, *args) as D
        }
    }
}
