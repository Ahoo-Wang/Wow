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
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies shutdown lifecycle and result-callback coordination.
 */
class BatchCoordinatorLifecycleTest {
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
            .expectError(BatchClosedException::class.java)
            .verify()
        coordinator.close()
    }

    @Test
    fun `cancelling a graceful stop observer should not cancel shared termination`() {
        val writerSubscribed = CountDownLatch(1)
        val writerResult = Sinks.one<List<BatchItemResult>>()
        val coordinator = coordinator {
            writerSubscribed.countDown()
            writerResult.asMono()
        }
        val appendResult = coordinator.submit(1).materialize().toFuture()
        val cancelledObserver = coordinator.stopGracefully().subscribe()

        try {
            writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            cancelledObserver.dispose()

            val survivingObserver = coordinator.stopGracefully().toFuture()
            survivingObserver.isDone.assert().isFalse()
            writerResult.tryEmitValue(listOf(BatchItemResult.Success))
                .assert()
                .isEqualTo(Sinks.EmitResult.OK)

            appendResult.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
            survivingObserver.get(1, TimeUnit.SECONDS)
            coordinator.close()
        } finally {
            writerResult.tryEmitValue(listOf(BatchItemResult.Success))
            runCatching(coordinator::close)
        }
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
            .expectError(BatchClosedException::class.java)
            .verify()
        factoryInvocations.get().assert().isEqualTo(0)
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
    fun `blocking callback should not delay another item in the same successful batch`() {
        val blockingCallbackEntered = CountDownLatch(1)
        val releaseBlockingCallback = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val blockedResult = coordinator.submit(1)
            .doOnSuccess {
                blockingCallbackEntered.countDown()
                releaseBlockingCallback.await()
            }.toFuture()
        val independentResult = coordinator.submit(2).toFuture()

        try {
            blockingCallbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            independentResult.get(1, TimeUnit.SECONDS)
        } finally {
            releaseBlockingCallback.countDown()
            blockedResult.get(1, TimeUnit.SECONDS)
            coordinator.close()
        }
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
        val writerSubscribed = CountDownLatch(1)
        val writerCancelled = CountDownLatch(1)
        val cancellationThread = AtomicReference<String>()
        val coordinator = coordinator(maxPendingItems = 2) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe {
                    writerSubscribed.countDown()
                }
                .doOnCancel {
                    cancellationThread.set(Thread.currentThread().name)
                    writerCancelled.countDown()
                }
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        val firstCloseError = assertThrows<BatchCloseTimeoutException> {
            coordinator.close(Duration.ofMillis(10))
        }
        val secondCloseError = kotlin.runCatching {
            coordinator.close(Duration.ofSeconds(1))
        }.exceptionOrNull()

        secondCloseError.assert().isSameAs(firstCloseError)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(firstCloseError)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(firstCloseError)
        writerCancelled.await(1, TimeUnit.SECONDS).assert().isTrue()
        cancellationThread.get()
            .startsWith("test-batch-window")
            .assert()
            .isTrue()
    }

    @Test
    fun `close timeout should fail queued and in flight callers with the same error`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(
            maxSize = 2,
            maxPendingItems = 4,
        ) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val callers = (1..4).map { item ->
            coordinator.submit(item).materialize().toFuture()
        }
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        val closeError = assertThrows<BatchCloseTimeoutException> {
            coordinator.close(Duration.ofMillis(10))
        }

        callers.forEach { caller ->
            caller.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(closeError)
        }
        assertThrows<BatchCloseTimeoutException> {
            coordinator.close(Duration.ofSeconds(1))
        }.assert().isSameAs(closeError)
    }

    @Test
    fun `close timeout should isolate a blocking item error callback`() {
        val writerSubscribed = CountDownLatch(1)
        val blockingCallbackEntered = CountDownLatch(1)
        val releaseBlockingCallback = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeError = AtomicReference<Throwable>()
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val blockedResult = coordinator.submit(1)
            .doOnError {
                blockingCallbackEntered.countDown()
                releaseBlockingCallback.await()
            }.materialize()
            .toFuture()
        val independentResult = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val closeThread = Thread(
            {
                closeError.set(
                    runCatching {
                        coordinator.close(Duration.ofMillis(10))
                    }.exceptionOrNull()
                )
                closeFinished.countDown()
            },
            "test-batch-close",
        ).apply {
            isDaemon = true
        }

        try {
            closeThread.start()
            blockingCallbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            closeFinished.await(1, TimeUnit.SECONDS).assert().isTrue()

            val terminalError = closeError.get()
            terminalError.assert().isInstanceOf(BatchCloseTimeoutException::class.java)
            independentResult.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isSameAs(terminalError)
        } finally {
            releaseBlockingCallback.countDown()
            closeThread.join(TimeUnit.SECONDS.toMillis(1))
        }
        blockedResult.get(1, TimeUnit.SECONDS)!!.throwable
            .assert()
            .isSameAs(closeError.get())
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
                .isInstanceOf(BatchClosedException::class.java)
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
}
