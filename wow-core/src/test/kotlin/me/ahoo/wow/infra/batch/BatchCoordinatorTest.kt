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
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Verifies batching, result isolation, and bounded admission behavior.
 */
class BatchCoordinatorTest {
    @Test
    fun `should isolate item results`() {
        val itemFailure = IllegalStateException("item failed")
        val coordinator = coordinator { items ->
            items.assert().containsExactly(1, 2)
            Mono.just(
                listOf(
                    BatchItemResult.Success,
                    BatchItemResult.Failure(itemFailure),
                )
            )
        }

        try {
            StepVerifier.create(
                Flux.merge(
                    coordinator.submit(1).materialize(),
                    coordinator.submit(2).materialize(),
                ).collectList()
            )
                .assertNext { signals ->
                    signals.count { it.isOnComplete }.assert().isEqualTo(1)
                    signals.single { it.isOnError }.throwable.assert().isSameAs(itemFailure)
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `writer failure should fail only the current batch`() {
        val batchFailure = IllegalStateException("batch failed")
        val invocation = AtomicInteger()
        val coordinator = coordinator {
            if (invocation.getAndIncrement() == 0) {
                Mono.error(batchFailure)
            } else {
                Mono.just(it.map { BatchItemResult.Success })
            }
        }

        try {
            StepVerifier.create(batchSignals(coordinator, 1, 2))
                .assertNext { signals ->
                    signals.assert().hasSize(2)
                    signals.all { it.throwable === batchFailure }.assert().isTrue()
                }
                .verifyComplete()

            StepVerifier.create(batchSignals(coordinator, 3, 4))
                .assertNext { signals ->
                    signals.assert().hasSize(2)
                    signals.all { it.isOnComplete }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `invalid result cardinality should fail the batch and continue`() {
        val invocation = AtomicInteger()
        val coordinator = coordinator {
            if (invocation.getAndIncrement() == 0) {
                Mono.just(listOf(BatchItemResult.Success))
            } else {
                Mono.just(it.map { BatchItemResult.Success })
            }
        }

        try {
            StepVerifier.create(batchSignals(coordinator, 1, 2))
                .assertNext { signals ->
                    signals.assert().hasSize(2)
                    signals.all {
                        it.throwable is BatchProtocolException
                    }.assert().isTrue()
                }
                .verifyComplete()

            StepVerifier.create(batchSignals(coordinator, 3, 4))
                .assertNext { signals ->
                    signals.all { it.isOnComplete }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `size and timeout flushes should use one batch thread`() {
        val writerThreads = ConcurrentHashMap.newKeySet<String>()
        val coordinator = coordinator(
            maxSize = 2,
            maxDelay = Duration.ofMillis(10),
        ) { items ->
            writerThreads.add(Thread.currentThread().name)
            Mono.just(items.map { BatchItemResult.Success })
        }

        try {
            batchSignals(coordinator, 1, 2)
                .block(Duration.ofSeconds(1))
            coordinator.submit(3)
                .block(Duration.ofSeconds(1))

            writerThreads.assert().hasSize(1)
            writerThreads.single()
                .startsWith("test-batch-window")
                .assert()
                .isTrue()
        } finally {
            coordinator.close(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `repeated timeout flushes should not strand the final item`() {
        val burstSize = 32
        val repetitions = 2_000
        val writtenItems = AtomicInteger()
        val coordinator = coordinator(
            maxSize = 512,
            maxDelay = Duration.ofMillis(1),
            maxPendingItems = 512,
        ) { items ->
            writtenItems.addAndGet(items.size)
            Mono.just(items.map { BatchItemResult.Success })
        }

        try {
            repeat(repetitions) {
                Flux.range(0, burstSize)
                    .flatMap(
                        { item ->
                            coordinator.submit {
                                LockSupport.parkNanos(30_000)
                                item
                            }
                        },
                        burstSize,
                    )
                    .then()
                    .block(Duration.ofSeconds(2))
            }
            writtenItems.get().assert().isEqualTo(burstSize * repetitions)
        } finally {
            coordinator.close(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `cancelled queued item should not be written or consume another callers result`() {
        val coordinator = coordinator { items ->
            items.assert().containsExactly(2)
            Mono.just(listOf(BatchItemResult.Success))
        }
        val cancelled = coordinator.submit(1).subscribe()
        cancelled.dispose()

        try {
            coordinator.submit(2)
                .test()
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `overflow should not invoke item factory`() {
        val factoryInvocations = AtomicInteger()
        val coordinator = coordinator(maxPendingItems = 2) {
            Mono.never()
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()

        coordinator.submit {
            factoryInvocations.incrementAndGet()
            3
        }
            .test()
            .expectError(BatchOverflowException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)

        val closeError = kotlin.runCatching {
            coordinator.close(Duration.ofMillis(10))
        }.exceptionOrNull()
        closeError.assert().isInstanceOf(BatchCloseTimeoutException::class.java)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
    }

    @Test
    fun `empty writer result should fail the batch and allow the next batch`() {
        val invocation = AtomicInteger()
        val coordinator = coordinator { items ->
            if (invocation.getAndIncrement() == 0) {
                Mono.empty()
            } else {
                Mono.just(items.map { BatchItemResult.Success })
            }
        }

        try {
            batchSignals(coordinator, 1, 2)
                .test()
                .assertNext { signals ->
                    signals.all {
                        it.throwable is BatchProtocolException
                    }.assert().isTrue()
                }
                .verifyComplete()
            batchSignals(coordinator, 3, 4)
                .test()
                .assertNext { signals ->
                    signals.all { it.isOnComplete }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `invalid options and blank name should be rejected`() {
        assertThrows<IllegalArgumentException> {
            BatchOptions(
                maxSize = 1,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            BatchOptions(
                maxSize = 2,
                maxDelay = Duration.ZERO,
                maxPendingItems = 2,
            )
        }
        assertThrows<IllegalArgumentException> {
            BatchOptions(
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            BatchCoordinator<Int>(
                name = " ",
                options = options(),
                writer = BatchWriter {
                    Mono.just(it.map { BatchItemResult.Success })
                },
            )
        }
        assertThrows<IllegalArgumentException> {
            BatchCoordinator<Int>(
                name = "invalid-lanes",
                options = options(),
                writer = BatchWriter {
                    Mono.just(it.map { BatchItemResult.Success })
                },
                laneCount = 0,
                laneSelector = { 0 },
            )
        }.message.assert().isEqualTo("laneCount must be greater than zero.")
    }

    @Test
    fun `invalid selected lane should fail only that submission and release capacity`() {
        val coordinator = BatchCoordinator<Int>(
            name = "invalid-selected-lane",
            options = options(maxPendingItems = 2),
            writer = BatchWriter { items ->
                Mono.just(items.map { BatchItemResult.Success })
            },
            laneCount = 2,
            laneSelector = { item ->
                if (item == 1) {
                    2
                } else {
                    0
                }
            },
        )

        try {
            coordinator.submit(1)
                .test()
                .expectErrorMatches { error ->
                    error is IllegalStateException &&
                        error.message ==
                        "Batch lane selector[invalid-selected-lane] returned 2 outside [0, 2)."
                }
                .verify()

            batchSignals(coordinator, 2, 3)
                .test()
                .assertNext { signals ->
                    signals.all { it.isOnComplete }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `item factory failure should release admission capacity`() {
        val factoryFailure = IllegalStateException("factory failed")
        val coordinator = coordinator(maxPendingItems = 2) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }

        try {
            coordinator.submit { throw factoryFailure }
                .test()
                .expectErrorMatches { it === factoryFailure }
                .verify()
            batchSignals(coordinator, 1, 2)
                .test()
                .assertNext { signals ->
                    signals.all { it.isOnComplete }.assert().isTrue()
                }
                .verifyComplete()
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `a fully cancelled buffered batch should be discarded`() {
        val writerInvocations = AtomicInteger()
        val coordinator = coordinator { items ->
            writerInvocations.incrementAndGet()
            Mono.just(items.map { BatchItemResult.Success })
        }
        val first = coordinator.submit(1).subscribe()
        first.dispose()

        coordinator.close()

        writerInvocations.get().assert().isEqualTo(0)
    }

    @Test
    fun `cancelled placeholders should preserve bounded queue admission`() {
        val firstBatchStarted = CountDownLatch(1)
        val factoryInvocations = AtomicInteger()
        val coordinator = coordinator(maxPendingItems = 3) {
            firstBatchStarted.countDown()
            Mono.never()
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        firstBatchStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

        coordinator.submit(3).subscribe().dispose()
        coordinator.submit(4).subscribe().dispose()
        coordinator.submit(5).subscribe().dispose()

        coordinator.submit {
            factoryInvocations.incrementAndGet()
            6
        }
            .test()
            .expectError(BatchOverflowException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)

        val closeError = kotlin.runCatching {
            coordinator.close(Duration.ofMillis(10))
        }.exceptionOrNull()
        closeError.assert().isInstanceOf(BatchCloseTimeoutException::class.java)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
    }
}
