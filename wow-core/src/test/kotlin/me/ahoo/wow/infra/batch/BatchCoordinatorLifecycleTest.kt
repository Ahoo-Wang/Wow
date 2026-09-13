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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies shutdown lifecycle and result-callback coordination.
 */
class BatchCoordinatorLifecycleTest {
    @Test
    fun `processor completing after installed failure should leave sealing to failure owner`() {
        val writerResult = Sinks.one<List<BatchItemResult>>()
        val coordinator = coordinator { writerResult.asMono() }
        val item = coordinator.submit(1).toFuture()
        val stopped = coordinator.stopGracefully().materialize().toFuture()
        val lifecycle = BatchCoordinator::class.java.getDeclaredField("lifecycle").let {
            it.isAccessible = true
            it.get(coordinator) as BatchLifecycle
        }
        val dispatcher = BatchCoordinator::class.java.getDeclaredField("resultDispatcher").let {
            it.isAccessible = true
            it.get(coordinator) as BatchResultDispatcher
        }
        val resultLock = BatchResultDispatcher::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(dispatcher)
        }
        val processorTermination = BatchCoordinator::class.java.getDeclaredField("processorTermination").let {
            it.isAccessible = true
            it.get(coordinator) as CompletableFuture<*>
        }
        val error = IllegalStateException("failure installed before publication")
        // Pause the failure owner immediately after installing failure, before publishing its snapshot.
        lifecycle.fail(error).assert().isInstanceOf(BatchLifecycle.FailureTransition.Installed::class.java)
        try {
            writerResult.tryEmitValue(listOf(BatchItemResult.Success))
            runCatching { processorTermination.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
                .assert().isInstanceOf(java.util.concurrent.ExecutionException::class.java)
            synchronized(resultLock) {
                BatchResultDispatcher::class.java.getDeclaredField("sealed").let {
                    it.isAccessible = true
                    it.getBoolean(dispatcher).assert().isFalse()
                }
            }
            stopped.isDone.assert().isFalse()
        } finally {
            // Resume the failure owner's publication/seal phase; this item already settled successfully.
            synchronized(resultLock) { dispatcher.seal() }
            dispatcher.shutdown()
        }
        item.get(1, TimeUnit.SECONDS)
        stopped.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(error)
    }

    @Test
    fun `admission should not wait for the result publication gate`() {
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        val dispatcher = BatchCoordinator::class.java.getDeclaredField("resultDispatcher").let {
            it.isAccessible = true
            it.get(coordinator)
        }
        val resultLock = BatchResultDispatcher::class.java.getDeclaredField("lock").let {
            it.isAccessible = true
            it.get(dispatcher)
        }
        try {
            synchronized(resultLock) {
                CompletableFuture.runAsync { coordinator.submit(1).materialize().subscribe() }
                    .get(1, TimeUnit.SECONDS)
            }
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `timeout after processor completed should preserve settled success and terminal failure`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        val first = coordinator.submit(1).doOnSuccess {
            callbackEntered.countDown()
            releaseCallback.await()
        }.toFuture()
        val second = coordinator.submit(2).toFuture()
        try {
            callbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            second.get(1, TimeUnit.SECONDS)
            val stopped = coordinator.stopGracefully().materialize().toFuture()
            val error = assertThrows<BatchCloseTimeoutException> { coordinator.close(Duration.ofMillis(20)) }
            assertThrows<BatchCloseTimeoutException> { coordinator.stop(Duration.ofSeconds(1)) }
                .assert().isSameAs(error)
            releaseCallback.countDown()
            first.get(1, TimeUnit.SECONDS)
            stopped.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(error)
        } finally {
            releaseCallback.countDown()
        }
    }

    @Test
    fun `notification saturation should reject new admission and recover after callbacks exit`() {
        val callbacksEntered = CountDownLatch(4)
        val releaseCallbacks = CountDownLatch(1)
        val writesFinished = CountDownLatch(4)
        val writerGate = Sinks.empty<Void>()
        val callbackCount = AtomicInteger()
        val coordinator = coordinator(maxPendingItems = 4) { items ->
            Mono.just<List<BatchItemResult>>(
                items.map { BatchItemResult.Success }
            ).doFinally { writesFinished.countDown() }
        }
        val first = (1..4).map {
            coordinator.submit(it).doOnSuccess {
                callbackCount.incrementAndGet()
                callbacksEntered.countDown()
                releaseCallbacks.await()
            }.toFuture()
        }
        try {
            writerGate.tryEmitEmpty()
            callbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            val queued = (5..8).map {
                coordinator.submit(it).doOnSuccess { callbackCount.incrementAndGet() }.toFuture()
            }
            writesFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
            resultExecutor(coordinator).queue.size.assert().isEqualTo(4)
            coordinator.submit(9).test().expectError(BatchOverflowException::class.java).verify()
            val stopped = coordinator.stopGracefully().toFuture()
            stopped.isDone.assert().isFalse()
            releaseCallbacks.countDown()
            (first + queued).forEach { it.get(1, TimeUnit.SECONDS) }
            stopped.get(1, TimeUnit.SECONDS)
            callbackCount.get().assert().isEqualTo(8)
        } finally {
            releaseCallbacks.countDown()
            coordinator.close()
        }
    }

    @Test
    fun `subscription handshake may replay result after synchronous close`() {
        val submitThread = Thread.currentThread()
        val closed = AtomicBoolean()
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        coordinator.submit(1)
            .doOnSubscribe {
                coordinator.close(Duration.ofSeconds(1))
                closed.set(true)
            }.doOnSuccess {
                closed.get().assert().isTrue()
                Thread.currentThread().assert().isSameAs(submitThread)
            }.test().verifyComplete()
    }

    @Test
    fun `graceful close observer can wait for a thread acquiring lifecycle lock`() {
        val writerResult = Sinks.one<List<BatchItemResult>>()
        val coordinator = coordinator { writerResult.asMono() }
        val item = coordinator.submit(1).toFuture()
        val lifecycle = BatchCoordinator::class.java.getDeclaredField("lifecycle").let {
            it.isAccessible = true
            it.get(coordinator) as BatchLifecycle
        }
        val observed = coordinator.stopGracefully().doOnSuccess {
            Thread.holdsLock(lifecycle.lock).assert().isFalse()
            CompletableFuture.runAsync { synchronized(lifecycle.lock) {} }
                .get(1, TimeUnit.SECONDS)
        }.toFuture()
        writerResult.tryEmitValue(listOf(BatchItemResult.Success))
        observed.get(1, TimeUnit.SECONDS)
        item.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `executor failure should fail shutdown without emitting on the writer thread`() {
        val emissions = AtomicInteger()
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        resultExecutor(coordinator).shutdown()
        val first = coordinator.submit(1).doOnTerminate { emissions.incrementAndGet() }.subscribe()
        val second = coordinator.submit(2).doOnTerminate { emissions.incrementAndGet() }.subscribe()
        try {
            val failure = coordinator.stopGracefully().materialize().block(Duration.ofSeconds(1))!!.throwable
            failure.assert().isInstanceOf(RejectedExecutionException::class.java)
            coordinator.submit(3).test().expectErrorMatches { it === failure }.verify()
            emissions.get().assert().isEqualTo(0)
            assertThrows<RejectedExecutionException> { coordinator.close() }.assert().isSameAs(failure)
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    private fun resultExecutor(coordinator: BatchCoordinator<*>): ThreadPoolExecutor {
        val dispatcher = BatchCoordinator::class.java.getDeclaredField("resultDispatcher").let {
            it.isAccessible = true
            it.get(coordinator)
        }
        return BatchResultDispatcher::class.java.getDeclaredField("executor").let {
            it.isAccessible = true
            it.get(dispatcher) as ThreadPoolExecutor
        }
    }

    @Test
    fun `failure callback may synchronously close without waiting for itself`() {
        val writerSubscribed = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val callbackError = AtomicReference<Throwable>()
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val first = coordinator.submit(1).doOnError {
            callbackError.set(runCatching { coordinator.close(Duration.ofSeconds(10)) }.exceptionOrNull())
            callbackFinished.countDown()
        }.materialize().toFuture()
        coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val error = assertThrows<BatchCloseTimeoutException> { coordinator.close(Duration.ofMillis(20)) }
        callbackFinished.await(1, TimeUnit.SECONDS).assert().isTrue()
        callbackError.get().assert().isSameAs(error)
        first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(error)
    }

    @Test
    fun `stop timeout should settle pending callers with the same terminal error`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val first = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        try {
            writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            val error = assertThrows<BatchCloseTimeoutException> {
                coordinator.stop(Duration.ofMillis(20))
            }
            first.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(error)
            second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(error)
        } finally {
            runCatching { coordinator.close(Duration.ofMillis(20)) }
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
            closeResult.get(1, TimeUnit.SECONDS)
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
