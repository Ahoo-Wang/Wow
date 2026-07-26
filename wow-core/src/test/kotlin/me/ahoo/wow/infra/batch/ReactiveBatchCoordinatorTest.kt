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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReactiveBatchCoordinatorTest {
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
                        it.throwable is ReactiveBatchProtocolException
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
    fun `close should flush a partial batch`() {
        val coordinator = coordinator { items ->
            items.assert().containsExactly(1)
            Mono.just(items.map { BatchItemResult.Success })
        }

        StepVerifier.create(coordinator.submit(1))
            .then {
                coordinator.close(Duration.ofSeconds(1))
            }
            .verifyComplete()
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
            .expectError(ReactiveBatchOverflowException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)

        val closeError = kotlin.runCatching {
            coordinator.close(Duration.ofMillis(10))
        }.exceptionOrNull()
        closeError.assert().isInstanceOf(ReactiveBatchCloseTimeoutException::class.java)
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
                        it.throwable is ReactiveBatchProtocolException
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
    fun `graceful stop should flush once and reject later submissions`() {
        val invocation = AtomicInteger()
        val coordinator = coordinator { items ->
            invocation.incrementAndGet()
            Mono.just(items.map { BatchItemResult.Success })
        }
        val result = coordinator.submit(1).materialize().toFuture()

        coordinator.stopGracefully()
            .test()
            .verifyComplete()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        invocation.get().assert().isEqualTo(1)
        coordinator.submit(2)
            .test()
            .expectError(ReactiveBatchClosedException::class.java)
            .verify()
        coordinator.close()
    }

    @Test
    fun `close should be idempotent and timeout must be positive`() {
        val coordinator = coordinator { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }

        coordinator.close()
        coordinator.close()
        kotlin.runCatching {
            coordinator.close(Duration.ZERO)
        }.exceptionOrNull()
            .assert()
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `invalid options and blank name should be rejected`() {
        assertThrows<IllegalArgumentException> {
            ReactiveBatchOptions(
                maxSize = 1,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            ReactiveBatchOptions(
                maxSize = 2,
                maxDelay = Duration.ZERO,
                maxPendingItems = 2,
            )
        }
        assertThrows<IllegalArgumentException> {
            ReactiveBatchOptions(
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 1,
            )
        }
        assertThrows<IllegalArgumentException> {
            ReactiveBatchCoordinator(
                name = " ",
                options = options(),
                writer = ReactiveBatchWriter {
                    Mono.just(it.map { BatchItemResult.Success })
                },
            )
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
    fun `lazy submission should observe a close before invoking its factory`() {
        val factoryInvocations = AtomicInteger()
        val coordinator = coordinator { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val lazySubmission = coordinator.submit {
            factoryInvocations.incrementAndGet()
            1
        }

        coordinator.close()

        lazySubmission
            .test()
            .expectError(ReactiveBatchClosedException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)
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
    fun `close from an item result callback should not deadlock result draining`() {
        val coordinator = coordinator { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val callbackInvocations = AtomicInteger()

        Flux.merge(
            coordinator.submit(1)
                .doOnSuccess {
                    coordinator.close(Duration.ofSeconds(1))
                    callbackInvocations.incrementAndGet()
                },
            coordinator.submit(2),
        )
            .then()
            .test()
            .verifyComplete()

        callbackInvocations.get().assert().isEqualTo(1)
        coordinator.close()
    }

    @Test
    fun `graceful stop from a result callback should wait for every result callback`() {
        val stopRequested = CountDownLatch(1)
        val secondCallbackEntered = CountDownLatch(1)
        val releaseSecondCallback = CountDownLatch(1)
        val stopResult = AtomicReference<CompletableFuture<Void?>>()
        val coordinator = coordinator { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }

        val callers = Flux.merge(
            coordinator.submit(1)
                .doOnSuccess {
                    stopResult.set(coordinator.stopGracefully().toFuture())
                    stopRequested.countDown()
                },
            coordinator.submit(2)
                .doOnSuccess {
                    secondCallbackEntered.countDown()
                    releaseSecondCallback.await()
                },
        ).then().toFuture()

        try {
            stopRequested.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondCallbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            stopResult.get().isDone.assert().isFalse()

            releaseSecondCallback.countDown()

            callers.get(1, TimeUnit.SECONDS)
            stopResult.get().get(1, TimeUnit.SECONDS)
        } finally {
            releaseSecondCallback.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `subsequent close after timeout should throw the same terminal failure`() {
        val coordinator = coordinator(maxPendingItems = 2) {
            Mono.never()
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()

        val firstCloseError = assertThrows<ReactiveBatchCloseTimeoutException> {
            coordinator.close(Duration.ofMillis(10))
        }
        val secondCloseError = kotlin.runCatching {
            coordinator.close(Duration.ofSeconds(1))
        }.exceptionOrNull()

        secondCloseError.assert().isSameAs(firstCloseError)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(firstCloseError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(firstCloseError)
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
            .expectError(ReactiveBatchOverflowException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)

        val closeError = kotlin.runCatching {
            coordinator.close(Duration.ofMillis(10))
        }.exceptionOrNull()
        closeError.assert().isInstanceOf(ReactiveBatchCloseTimeoutException::class.java)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
    }

    @Test
    fun `close racing item construction should reject the item`() {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = coordinator { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }

        try {
            val submission = CompletableFuture.supplyAsync(
                {
                    coordinator.submit {
                        factoryEntered.countDown()
                        releaseFactory.await()
                        1
                    }.materialize().block()
                },
                executor,
            )
            factoryEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            val closeResult = coordinator.stopGracefully().toFuture()
            releaseFactory.countDown()

            submission.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(ReactiveBatchClosedException::class.java)
            closeResult.get(1, TimeUnit.SECONDS)
        } finally {
            releaseFactory.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun `interrupted close should fail pending callers with the same error`() {
        val writerSubscribed = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closeError = AtomicReference<Throwable>()
        val closeThreadInterrupted = AtomicBoolean()
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val closeThread = Thread {
            closeEntered.countDown()
            closeError.set(
                kotlin.runCatching {
                    coordinator.close(Duration.ofSeconds(10))
                }.exceptionOrNull()
            )
            closeThreadInterrupted.set(Thread.currentThread().isInterrupted)
        }

        closeThread.start()
        closeEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        closeThread.interrupt()
        closeThread.join(TimeUnit.SECONDS.toMillis(1))

        closeThread.isAlive.assert().isFalse()
        closeThreadInterrupted.get().assert().isTrue()
        closeError.get()
            .assert()
            .isInstanceOf(IllegalStateException::class.java)
        closeError.get().cause.assert().isInstanceOf(InterruptedException::class.java)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError.get())
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError.get())
    }

    private fun batchSignals(
        coordinator: ReactiveBatchCoordinator<Int>,
        first: Int,
        second: Int,
    ) = Flux.merge(
        coordinator.submit(first).materialize(),
        coordinator.submit(second).materialize(),
    ).collectList()

    private fun coordinator(
        maxPendingItems: Int = 8,
        writer: (List<Int>) -> Mono<List<BatchItemResult>>,
    ): ReactiveBatchCoordinator<Int> {
        return ReactiveBatchCoordinator(
            name = "test",
            options = options(maxPendingItems),
            writer = ReactiveBatchWriter(writer),
        )
    }

    private fun options(maxPendingItems: Int = 8): ReactiveBatchOptions {
        return ReactiveBatchOptions(
            maxSize = 2,
            maxDelay = Duration.ofHours(1),
            maxPendingItems = maxPendingItems,
        )
    }
}
