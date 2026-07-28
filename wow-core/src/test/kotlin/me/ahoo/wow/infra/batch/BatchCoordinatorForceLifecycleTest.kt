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
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Verifies prompt logical force-stop independently of blocking physical cleanup.
 */
class BatchCoordinatorForceLifecycleTest {

    @Test
    fun `force stop cancels in flight work and fails pending callers immediately`() {
        val writerSubscribed = CountDownLatch(1)
        val writerCancelled = CountDownLatch(1)
        val coordinator = coordinator(maxSize = 2) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
                .doOnCancel { writerCancelled.countDown() }
        }
        val firstResult = coordinator.submit(1).materialize().toFuture()
        val secondResult = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        coordinator.forceStop()

        writerCancelled.await(1, TimeUnit.SECONDS).assert().isTrue()
        listOf(firstResult, secondResult).forEach { result ->
            result.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(BatchClosedException::class.java)
        }
    }

    @Test
    fun `force stop retains terminal errors beyond callback worker capacity`() {
        val signalCount = 69
        val blockingCallbacksEntered = CountDownLatch(4)
        val releaseBlockingCallbacks = CountDownLatch(1)
        val coordinator = coordinator(
            maxSize = signalCount,
            maxPendingItems = signalCount,
        ) {
            Mono.never()
        }
        val callers = (1..signalCount).map { item ->
            coordinator.submit(item)
                .doOnError {
                    if (item <= 4) {
                        blockingCallbacksEntered.countDown()
                        while (releaseBlockingCallbacks.count > 0) {
                            try {
                                releaseBlockingCallbacks.await()
                            } catch (_: InterruptedException) {
                                // Model an uncooperative subscriber callback.
                            }
                        }
                    }
                }
                .materialize()
                .toFuture()
        }
        awaitCoordinatorCount(coordinator::pendingItemCount, signalCount)

        try {
            coordinator.forceStop()
            blockingCallbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseBlockingCallbacks.countDown()
        }
        callers.forEach { caller ->
            caller.get(1, TimeUnit.SECONDS)!!.throwable.assert()
                .isInstanceOf(BatchClosedException::class.java)
        }
    }

    @Test
    fun `concurrent force stops terminate every accepted request`() {
        val requestCount = 256
        val forceCallers = 4
        val startForce = CountDownLatch(1)
        val forceExecutor = Executors.newFixedThreadPool(forceCallers)
        val coordinator = coordinator(
            maxSize = requestCount,
            maxPendingItems = requestCount,
        ) {
            Mono.never()
        }
        val callers = (1..requestCount).map { item ->
            coordinator.submit(item).materialize().toFuture()
        }
        awaitCoordinatorCount(coordinator::pendingItemCount, requestCount)

        try {
            val forceStops = List(forceCallers) {
                CompletableFuture.runAsync(
                    {
                        startForce.await()
                        coordinator.forceStop()
                    },
                    forceExecutor,
                )
            }
            startForce.countDown()
            CompletableFuture.allOf(*forceStops.toTypedArray())
                .get(1, TimeUnit.SECONDS)

            callers.forEach { caller ->
                caller.get(1, TimeUnit.SECONDS)!!.throwable
                    .assert()
                    .isInstanceOf(BatchClosedException::class.java)
            }
        } finally {
            startForce.countDown()
            forceExecutor.shutdownNow()
        }
    }

    @Test
    fun `force stop should detach a cancelled queued placeholder`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(
            maxSize = 2,
            maxDelay = Duration.ofMillis(1),
            maxPendingItems = 2,
        ) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
        }
        val inFlight = coordinator.submit(1).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val cancelled = coordinator.submit(2).subscribe()
        awaitCoordinatorCount(coordinator::queuedItemCount, 1)

        cancelled.dispose()
        awaitCoordinatorCount(coordinator::pendingItemCount, 1)
        coordinator.queuedItemCount.assert().isEqualTo(1)

        coordinator.forceStop()

        coordinator.pendingItemCount.assert().isZero()
        coordinator.queuedItemCount.assert().isZero()
        inFlight.get(1, TimeUnit.SECONDS)!!.throwable
            .assert()
            .isInstanceOf(BatchClosedException::class.java)
    }

    @Test
    fun `close timeout should detach a cancelled queued placeholder`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(
            maxSize = 2,
            maxDelay = Duration.ofMillis(1),
            maxPendingItems = 2,
        ) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
        }
        val inFlight = coordinator.submit(1).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val cancelled = coordinator.submit(2).subscribe()
        awaitCoordinatorCount(coordinator::queuedItemCount, 1)

        cancelled.dispose()
        awaitCoordinatorCount(coordinator::pendingItemCount, 1)
        coordinator.queuedItemCount.assert().isEqualTo(1)

        val closeFailure = assertThrows<BatchCloseTimeoutException> {
            coordinator.close(Duration.ofMillis(10))
        }

        coordinator.pendingItemCount.assert().isZero()
        coordinator.queuedItemCount.assert().isZero()
        inFlight.get(1, TimeUnit.SECONDS)!!.throwable
            .assert()
            .isSameAs(closeFailure)
    }

    @Test
    fun `force stop should interrupt a blocking writer and terminate with the force failure`() {
        val writerEntered = CountDownLatch(1)
        val writerInterrupted = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val coordinator = coordinator {
            writerEntered.countDown()
            try {
                releaseWriter.await()
            } catch (error: InterruptedException) {
                writerInterrupted.countDown()
                throw error
            }
            Mono.just(it.map { BatchItemResult.Success })
        }
        val callers = listOf(
            coordinator.submit(1).materialize().toFuture(),
            coordinator.submit(2).materialize().toFuture(),
        )
        writerEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        val termination = coordinator.stopGracefully().materialize().toFuture()

        try {
            coordinator.forceStop()

            writerInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
            callers.forEach { caller ->
                caller.get(1, TimeUnit.SECONDS)!!.throwable
                    .assert()
                    .isInstanceOf(BatchClosedException::class.java)
            }
            termination.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(BatchClosedException::class.java)
        } finally {
            releaseWriter.countDown()
        }
    }

    @Test
    fun `force stop should propagate writer cancellation even when the batch scheduler is blocked`() {
        val writerSubscribed = CountDownLatch(1)
        val writerCancelled = CountDownLatch(1)
        val releaseWriter = AtomicBoolean()
        val coordinator = coordinator(maxSize = 2) {
            Mono.create<List<BatchItemResult>> { sink ->
                sink.onCancel(writerCancelled::countDown)
                writerSubscribed.countDown()
                while (!releaseWriter.get()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                    Thread.interrupted()
                }
            }
        }
        val callers = (1..2).map { item ->
            coordinator.submit(item).materialize().toFuture()
        }

        try {
            writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            coordinator.forceStop()

            writerCancelled.await(1, TimeUnit.SECONDS).assert().isTrue()
            callers.forEach { caller ->
                caller.get(1, TimeUnit.SECONDS)!!.throwable
                    .assert()
                    .isInstanceOf(BatchClosedException::class.java)
            }
        } finally {
            releaseWriter.set(true)
        }
    }

    @Test
    fun `force stop should return while a writer cancellation hook is blocked`() {
        val writerSubscribed = CountDownLatch(1)
        val cancellationStarted = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val allowCancellationReturn = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = coordinator(maxSize = 2) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
                .doOnCancel {
                    cancellationStarted.countDown()
                    try {
                        while (true) {
                            try {
                                allowCancellationReturn.await()
                                break
                            } catch (_: InterruptedException) {
                                // A user callback may ignore interruption. The
                                // force-stop caller must still remain non-blocking.
                            }
                        }
                    } finally {
                        cancellationFinished.countDown()
                    }
                }
        }
        val callers = (1..2).map { item ->
            coordinator.submit(item).materialize().toFuture()
        }
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        try {
            val forceStop = CompletableFuture.runAsync(coordinator::forceStop, executor)

            cancellationStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            forceStop.get(250, TimeUnit.MILLISECONDS)
            callers.forEach { caller ->
                caller.get(1, TimeUnit.SECONDS)!!.throwable
                    .assert()
                    .isInstanceOf(BatchClosedException::class.java)
            }
        } finally {
            allowCancellationReturn.countDown()
            cancellationFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop should return while a graceful stop observer is blocked`() {
        val writerSubscribed = CountDownLatch(1)
        val observerEntered = CountDownLatch(1)
        val observerFinished = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = coordinator {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
        }
        val results = listOf(
            coordinator.submit(1).materialize().toFuture(),
            coordinator.submit(2).materialize().toFuture(),
        )
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        var observer: Disposable? = null

        try {
            observer = coordinator.stopGracefully()
                .doOnError { error ->
                    if (error is BatchClosedException) {
                        observerEntered.countDown()
                        while (releaseObserver.count > 0) {
                            try {
                                releaseObserver.await()
                            } catch (_: InterruptedException) {
                                // Model an uncooperative termination observer.
                            }
                        }
                    }
                }.doFinally {
                    observerFinished.countDown()
                }.subscribe({}, {})
            val forceStop = CompletableFuture.runAsync(coordinator::forceStop, executor)

            observerEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            forceStop.get(1, TimeUnit.SECONDS)
            results.forEach { result ->
                result.get(1, TimeUnit.SECONDS)!!.throwable
                    .assert()
                    .isInstanceOf(BatchClosedException::class.java)
            }
        } finally {
            releaseObserver.countDown()
            observerFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
            observer?.dispose()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop should interrupt a blocking result callback before termination`() {
        val callbackEntered = CountDownLatch(1)
        val callbackInterrupted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val coordinator = coordinator {
            Mono.just(it.map { BatchItemResult.Success })
        }
        val caller = coordinator.submit(1)
            .doOnSuccess {
                callbackEntered.countDown()
                try {
                    releaseCallback.await()
                } catch (_: InterruptedException) {
                    callbackInterrupted.countDown()
                }
            }.toFuture()
        val secondCaller = coordinator.submit(2).toFuture()
        callbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        val termination = coordinator.stopGracefully().materialize().toFuture()

        try {
            coordinator.forceStop()

            callbackInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
            caller.get(1, TimeUnit.SECONDS)
            secondCaller.get(1, TimeUnit.SECONDS)
            termination.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(BatchClosedException::class.java)
        } finally {
            releaseCallback.countDown()
        }
    }

    private fun awaitCoordinatorCount(
        count: () -> Int,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (count() != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        count().assert().isEqualTo(expected)
    }
}
