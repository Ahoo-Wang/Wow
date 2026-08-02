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

import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Partitions submissions into a fixed number of serial batch lanes.
 *
 * Equal ordering keys are assigned to the same lane. Batch writer invocations
 * inside one lane are serial, while different lanes may invoke [writer]
 * concurrently. The coordinator keeps one global admission bound and one
 * lifecycle across all lanes.
 *
 * Multiple items with the same key may be included in one native batch. This
 * type does not impose an item execution order inside a protocol-specific batch;
 * a writer that requires such an order must provide it.
 *
 * When [laneCount] is greater than one, [keySelector] runs during lazy
 * submission and must be deterministic and non-blocking. A selector failure
 * rejects only that submission. Ordering keys must obey the usual
 * equality/hash-code contract.
 */
class KeyedBatchCoordinator<T : Any, K : Any>(
    val name: String,
    val options: BatchOptions,
    val laneCount: Int,
    private val keySelector: (T) -> K,
    writer: BatchWriter<T>,
) : GracefullyStoppable {
    init {
        require(laneCount > 0) {
            "laneCount must be greater than zero."
        }
        require(laneCount <= options.maxPendingItems) {
            "laneCount must be less than or equal to maxPendingItems."
        }
    }

    private val delegate = BatchCoordinator(
        name = name,
        options = options,
        writer = writer,
        laneCount = laneCount,
        laneSelector = { item ->
            Math.floorMod(keySelector(item).hashCode(), laneCount)
        },
    )

    fun submit(item: T): Mono<Void> = delegate.submit(item)

    fun submit(itemFactory: () -> T): Mono<Void> = delegate.submit(itemFactory)

    override fun stopGracefully(): Mono<Void> = delegate.stopGracefully()

    override fun close() = delegate.close()

    fun close(timeout: Duration) = delegate.close(timeout)
}
