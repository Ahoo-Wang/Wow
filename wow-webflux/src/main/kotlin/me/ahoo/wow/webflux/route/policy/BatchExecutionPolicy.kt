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

package me.ahoo.wow.webflux.route.policy

import org.reactivestreams.Publisher
import reactor.core.publisher.Flux

class BatchExecutionPolicy(
    val concurrency: Int = 1,
    val prefetch: Int = 1
) {
    init {
        require(concurrency > 0) {
            "concurrency must be greater than 0."
        }
        require(prefetch > 0) {
            "prefetch must be greater than 0."
        }
    }

    fun <T : Any, R : Any> apply(source: Flux<T>, mapper: (T) -> Publisher<out R>): Flux<R> {
        return source.flatMapSequential(mapper, concurrency, prefetch)
    }
}
