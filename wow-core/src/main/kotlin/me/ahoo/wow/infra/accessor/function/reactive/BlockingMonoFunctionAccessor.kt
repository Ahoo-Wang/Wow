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

import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import kotlin.reflect.KFunction

/**
 * MonoFunctionAccessor wrapper that handles blocking operations by scheduling them on a separate thread.
 * This accessor is used for functions annotated with @Blocking to ensure they don't block
 * the reactive event loop. It automatically switches to a bounded elastic scheduler
 * when running on a non-blocking thread.
 *
 * @param T the type of the target object
 * @param D the type of data in the Mono
 * @property monoFunctionAccessor the underlying mono function accessor
 * @property scheduler the scheduler to use for blocking operations (defaults to bounded elastic)
 */
class BlockingMonoFunctionAccessor<T, D>(
    private val monoFunctionAccessor: MonoFunctionAccessor<T, Mono<D>>,
    private val scheduler: Scheduler = Schedulers.boundedElastic()
) : MonoFunctionAccessor<T, Mono<D>> {
    /**
     * The underlying Kotlin function, delegated from the wrapped accessor.
     */
    override val function: KFunction<*>
        get() = monoFunctionAccessor.function

    /**
     * Invokes the function and ensures blocking operations are scheduled appropriately.
     * Uses the toBlockable extension to automatically handle thread scheduling for blocking operations.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function
     * @return a Mono containing the function result, scheduled appropriately for blocking operations
     */
    override operator fun invoke(
        target: T,
        args: Array<Any?>
    ): Mono<D> = monoFunctionAccessor.invoke(target, args).toBlockable(scheduler)
}

/**
 * Extension function that makes a Mono blockable by scheduling it on a separate thread if needed.
 * If the current thread is non-blocking (reactive), it subscribes the Mono on the provided scheduler.
 * If already on a blocking thread, returns the Mono unchanged.
 *
 * @param T the type of data in the Mono
 * @param scheduler the scheduler to use for blocking operations (defaults to bounded elastic)
 * @return a Mono that will execute on an appropriate thread for blocking operations
 */
fun <T> Mono<T>.toBlockable(scheduler: Scheduler = Schedulers.boundedElastic()): Mono<T> {
    if (Schedulers.isInNonBlockingThread()) {
        return this.subscribeOn(scheduler)
    }
    return this
}
