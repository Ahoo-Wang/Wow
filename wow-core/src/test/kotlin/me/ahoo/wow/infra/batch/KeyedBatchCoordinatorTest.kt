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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KeyedBatchCoordinatorTest {
    @Test
    fun `same lane batches should be written serially`() {
        val firstBatchStarted = CountDownLatch(1)
        val secondBatchStarted = CountDownLatch(1)
        val releaseFirstBatch = Sinks.one<Void>()
        val writerInvocations = AtomicInteger()
        val coordinator = coordinator { items ->
            when (writerInvocations.getAndIncrement()) {
                0 -> {
                    firstBatchStarted.countDown()
                    releaseFirstBatch.asMono()
                        .thenReturn(items.successResults())
                }

                else -> {
                    secondBatchStarted.countDown()
                    Mono.just(items.successResults())
                }
            }
        }
        val result = Flux.range(1, 4)
            .flatMap(
                { id -> coordinator.submit(KeyedItem(key = 0, id = id)) },
                4,
            )
            .then()
            .toFuture()

        try {
            firstBatchStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondBatchStarted.await(50, TimeUnit.MILLISECONDS).assert().isFalse()

            releaseFirstBatch.tryEmitEmpty().isSuccess.assert().isTrue()
            result.get(1, TimeUnit.SECONDS)

            secondBatchStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            writerInvocations.get().assert().isEqualTo(2)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `different lanes should write concurrently`() {
        val batchesStarted = CountDownLatch(2)
        val releaseBatches = Sinks.one<Void>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val coordinator = coordinator { items ->
            Mono.defer {
                val currentInFlight = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(currentInFlight, ::maxOf)
                batchesStarted.countDown()
                releaseBatches.asMono()
                    .thenReturn(items.successResults())
                    .doFinally {
                        inFlight.decrementAndGet()
                    }
            }
        }
        val result = Flux.just(
            KeyedItem(key = 0, id = 1),
            KeyedItem(key = 1, id = 2),
            KeyedItem(key = 0, id = 3),
            KeyedItem(key = 1, id = 4),
        )
            .flatMap(coordinator::submit, 4)
            .then()
            .toFuture()

        try {
            batchesStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            maxInFlight.get().assert().isEqualTo(2)

            releaseBatches.tryEmitEmpty().isSuccess.assert().isTrue()
            result.get(1, TimeUnit.SECONDS)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `pending admission should remain globally bounded across lanes`() {
        val batchesStarted = CountDownLatch(2)
        val coordinator = coordinator(maxPendingItems = 4) {
            batchesStarted.countDown()
            Mono.never()
        }
        val pending = listOf(
            coordinator.submit(KeyedItem(key = 0, id = 1)).materialize().toFuture(),
            coordinator.submit(KeyedItem(key = 1, id = 2)).materialize().toFuture(),
            coordinator.submit(KeyedItem(key = 0, id = 3)).materialize().toFuture(),
            coordinator.submit(KeyedItem(key = 1, id = 4)).materialize().toFuture(),
        )

        batchesStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        coordinator.submit(KeyedItem(key = 0, id = 5))
            .test()
            .expectError(BatchOverflowException::class.java)
            .verify()

        val closeError = assertThrows<BatchCloseTimeoutException> {
            coordinator.close(Duration.ofMillis(10))
        }
        pending.forEach { result ->
            result.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        }
    }

    @Test
    fun `close should flush partial batches in every lane`() {
        val writtenKeys = mutableSetOf<Int>()
        val coordinator = coordinator(
            maxSize = 8,
            maxDelay = Duration.ofHours(1),
        ) { items ->
            synchronized(writtenKeys) {
                writtenKeys += items.map(KeyedItem::key)
            }
            Mono.just(items.successResults())
        }
        val first = coordinator.submit(KeyedItem(key = 0, id = 1)).toFuture()
        val second = coordinator.submit(KeyedItem(key = 1, id = 2)).toFuture()

        coordinator.close(Duration.ofSeconds(1))

        first.get(1, TimeUnit.SECONDS)
        second.get(1, TimeUnit.SECONDS)
        writtenKeys.assert().containsExactlyInAnyOrder(0, 1)
    }

    @Test
    fun `lane count should be positive`() {
        val error = assertThrows<IllegalArgumentException> {
            KeyedBatchCoordinator(
                name = "invalid-lanes",
                options = options(),
                laneCount = 0,
                keySelector = KeyedItem::key,
                writer = BatchWriter { items ->
                    Mono.just(items.successResults())
                },
            )
        }

        error.message.assert().isEqualTo("laneCount must be greater than zero.")
    }

    @Test
    fun `lane count should not exceed pending item capacity`() {
        val error = assertThrows<IllegalArgumentException> {
            KeyedBatchCoordinator(
                name = "excessive-lanes",
                options = options(maxPendingItems = 8),
                laneCount = 9,
                keySelector = KeyedItem::key,
                writer = BatchWriter { items ->
                    Mono.just(items.successResults())
                },
            )
        }

        error.message.assert()
            .isEqualTo("laneCount must be less than or equal to maxPendingItems.")
    }

    private fun coordinator(
        maxSize: Int = 2,
        maxDelay: Duration = Duration.ofSeconds(1),
        maxPendingItems: Int = 8,
        writer: (List<KeyedItem>) -> Mono<List<BatchItemResult>>,
    ): KeyedBatchCoordinator<KeyedItem, Int> {
        return KeyedBatchCoordinator(
            name = "keyed-test",
            options = options(maxSize, maxDelay, maxPendingItems),
            laneCount = 2,
            keySelector = KeyedItem::key,
            writer = BatchWriter(writer),
        )
    }

    private fun options(
        maxSize: Int = 2,
        maxDelay: Duration = Duration.ofSeconds(1),
        maxPendingItems: Int = 8,
    ): BatchOptions {
        return BatchOptions(
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingItems = maxPendingItems,
        )
    }

    private fun List<KeyedItem>.successResults(): List<BatchItemResult> {
        return map { BatchItemResult.Success }
    }

    private data class KeyedItem(
        val key: Int,
        val id: Int,
    )
}
