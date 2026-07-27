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

package me.ahoo.wow.infra.batch

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal fun batchSignals(
    coordinator: BatchCoordinator<Int>,
    first: Int,
    second: Int,
) = Flux.merge(
    coordinator.submit(first).materialize(),
    coordinator.submit(second).materialize(),
).collectList()

internal fun coordinator(
    maxSize: Int = 2,
    maxDelay: Duration = Duration.ofHours(1),
    maxPendingItems: Int = 8,
    writer: (List<Int>) -> Mono<List<BatchItemResult>>,
): BatchCoordinator<Int> {
    return BatchCoordinator(
        name = "test",
        options = options(
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingItems = maxPendingItems,
        ),
        writer = BatchWriter(writer),
    )
}

internal fun options(
    maxSize: Int = 2,
    maxDelay: Duration = Duration.ofHours(1),
    maxPendingItems: Int = 8,
): BatchOptions {
    return BatchOptions(
        maxSize = maxSize,
        maxDelay = maxDelay,
        maxPendingItems = maxPendingItems,
    )
}
