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

package me.ahoo.wow.benchmark.workload

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Executes multiple asynchronous operations through one subscription boundary.
 *
 * The benchmark invokes [execute] once and blocks only on the returned aggregate result,
 * allowing per-subscription and per-block costs to be amortized across [size] operations.
 */
internal class ConcurrentBatchWorkload(
    val size: Int,
    val concurrency: Int,
) {
    init {
        require(size > 0) {
            "size must be greater than 0."
        }
        require(concurrency > 0) {
            "concurrency must be greater than 0."
        }
    }

    fun execute(operation: () -> Mono<*>): Mono<Long> {
        return Flux.range(0, size)
            .flatMap(
                { operation().thenReturn(Unit) },
                concurrency,
                1,
            ).count()
    }
}
