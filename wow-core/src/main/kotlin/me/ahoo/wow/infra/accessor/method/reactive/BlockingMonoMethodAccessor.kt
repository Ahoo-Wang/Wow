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

import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.lang.reflect.Method

class BlockingMonoMethodAccessor<T, D>(
    private val monoMethodAccessor: MonoMethodAccessor<T, Mono<D>>,
    private val scheduler: Scheduler = Schedulers.boundedElastic()
) :
    MonoMethodAccessor<T, Mono<D>> {

    override val method: Method
        get() = monoMethodAccessor.method

    override operator fun invoke(target: T, args: Array<Any?>): Mono<D> {
        return monoMethodAccessor.invoke(target, args).toBlockable(scheduler)
    }
}

fun <T> Mono<T>.toBlockable(scheduler: Scheduler = Schedulers.boundedElastic()): Mono<T> {
    if (Schedulers.isInNonBlockingThread()) {
        return this.subscribeOn(scheduler)
    }
    return this
}
