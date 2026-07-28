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
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class BatchCoordinatorFailureConcurrencyTest {

    @Test
    fun `failure fills the settled to dispatch handoff gap`() {
        val handoffEntered = CountDownLatch(1)
        val releaseHandoff = CountDownLatch(1)
        val failure = IllegalStateException("handoff-failure")
        val coordinator = coordinator(
            maxSize = 2,
            maxDelay = Duration.ofMillis(1),
            maxPendingItems = 2,
            beforeResultDispatch = {
                handoffEntered.countDown()
                awaitIgnoringInterrupt(releaseHandoff)
            },
        ) {
            Mono.just(listOf(BatchItemResult.Success))
        }
        val caller = coordinator.submit(1).materialize().toFuture()
        val failureExecutor = Executors.newSingleThreadExecutor()

        try {
            handoffEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            val failureCall = failureExecutor.submit {
                coordinator.reportFailure(failure)
            }
            failureCall.isDone.assert().isFalse()
            caller.isDone.assert().isFalse()

            releaseHandoff.countDown()
            failureCall.get(1, TimeUnit.SECONDS)

            caller.get(1, TimeUnit.SECONDS)!!.throwable.assert().isNull()
            coordinator.pendingItemCount.assert().isZero()
            coordinator.queuedItemCount.assert().isZero()
        } finally {
            releaseHandoff.countDown()
            failureExecutor.shutdownNow()
        }

        coordinator.stopGracefully()
            .materialize()
            .block(Duration.ofSeconds(1))!!
            .throwable
            .assert()
            .isSameAs(failure)
    }

    @Test
    fun `terminal failure detaches a cancelled queued placeholder`() {
        val writerSubscribed = CountDownLatch(1)
        val failure = IllegalStateException("terminal")
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
        coordinator.reportFailure(failure)

        inFlight.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(failure)
        awaitCoordinatorCount(coordinator::pendingItemCount, 0)
        awaitCoordinatorCount(coordinator::queuedItemCount, 0)
    }

    @Test
    fun `force stop is not blocked by an earlier failure cleanup`() {
        val writerSubscribed = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val allowCancellationReturn = CountDownLatch(1)
        val failure = IllegalStateException("lane-failure")
        val coordinator = BatchCoordinator(
            name = "failure-overlap",
            options = options(
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 2,
            ),
            writer = BatchWriter<Int> {
                Mono.never<List<BatchItemResult>>()
                    .doOnSubscribe { writerSubscribed.countDown() }
                    .doOnCancel {
                        cancellationEntered.countDown()
                        while (true) {
                            try {
                                allowCancellationReturn.await()
                                break
                            } catch (_: InterruptedException) {
                                // Simulates a user cancellation hook that ignores interruption.
                            }
                        }
                    }
            },
            laneCount = 2,
            laneSelector = { item -> item },
        )
        val caller = coordinator.submit(0).materialize().toFuture()
        val failureExecutor = Executors.newSingleThreadExecutor()
        val forceExecutor = Executors.newSingleThreadExecutor()

        try {
            writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            val failureCleanup = failureExecutor.submit {
                coordinator.reportFailure(failure)
            }
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            coordinator.areLanesDetached.assert().isTrue()
            coordinator.areResultCallbacksDetached.assert().isFalse()

            CompletableFuture.runAsync(coordinator::forceStop, forceExecutor)
                .get(250, TimeUnit.MILLISECONDS)

            failureCleanup.isDone.assert().isFalse()
            coordinator.areResultCallbacksDetached.assert().isTrue()
            caller.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isSameAs(failure)
            coordinator.stopGracefully()
                .materialize()
                .block(Duration.ofSeconds(1))!!
                .throwable
                .assert()
                .isSameAs(failure)
        } finally {
            allowCancellationReturn.countDown()
            failureExecutor.shutdownNow()
            forceExecutor.shutdownNow()
        }
    }

    @Test
    fun `background lane cleanup reports separately without mutating terminal failure`() {
        val writerSubscribed = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val allowCleanupFailure = CountDownLatch(1)
        val cleanupReported = CountDownLatch(1)
        val cleanupFailure = IllegalStateException("cleanup-failure")
        val reportedFailure = AtomicReference<Throwable>()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val coordinator = BatchCoordinator(
            name = "stable-terminal-failure",
            options = options(
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 2,
            ),
            writer = BatchWriter<Int> {
                Mono.never<List<BatchItemResult>>()
                    .doOnSubscribe { writerSubscribed.countDown() }
            },
            laneCount = 1,
            laneSelector = { 0 },
            forceLaneCleanupExecutor = cleanupExecutor,
            detachedProcessorDisposer = {
                cleanupEntered.countDown()
                allowCleanupFailure.await()
                cleanupFailure
            },
            forceLaneCleanupFailureHandler = { failure ->
                reportedFailure.set(failure)
                cleanupReported.countDown()
            },
        )
        val caller = coordinator.submit(1).materialize().toFuture()

        try {
            writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            coordinator.forceStop()
            val terminalFailure = coordinator.stopGracefully()
                .materialize()
                .block(Duration.ofSeconds(1))!!
                .throwable!!
            val suppressedBeforeCleanup = terminalFailure.suppressedExceptions.toList()
            cleanupEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            allowCleanupFailure.countDown()
            cleanupReported.await(1, TimeUnit.SECONDS).assert().isTrue()

            reportedFailure.get().assert().isSameAs(cleanupFailure)
            terminalFailure.suppressedExceptions.toList()
                .assert()
                .isEqualTo(suppressedBeforeCleanup)
            caller.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isSameAs(terminalFailure)
        } finally {
            allowCleanupFailure.countDown()
            cleanupExecutor.shutdownNow()
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

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Keep the lane paused at the deterministic result handoff seam.
            }
        }
    }
}
