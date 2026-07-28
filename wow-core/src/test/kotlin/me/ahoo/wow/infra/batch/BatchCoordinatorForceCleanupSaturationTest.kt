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
import reactor.core.publisher.Signal
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class BatchCoordinatorForceCleanupSaturationTest {
    @Test
    fun `saturation should preserve prompt logical detach with bounded cleanup`() {
        val fixture = newFixture()
        val callers = fixture.coordinators.mapIndexed { index, coordinator ->
            coordinator.submit(index).materialize().toFuture()
        }
        val terminations = fixture.coordinators.map { coordinator ->
            coordinator.stopGracefully().materialize().toFuture()
        }

        try {
            fixture.writersSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
            saturateCleanup(fixture)
            assertLogicalDetach(fixture.coordinators)
            assertTerminalSignals(terminations)
            assertTerminalSignals(callers)
        } finally {
            fixture.releaseCancellations.countDown()
        }
        awaitAtMost(
            fixture.cancellationsEntered,
            COORDINATOR_COUNT - CLEANUP_CAPACITY,
        )
        fixture.cancellationsEntered.count
            .assert()
            .isEqualTo((COORDINATOR_COUNT - CLEANUP_CAPACITY).toLong())
    }

    private fun saturateCleanup(fixture: Fixture) {
        fixture.coordinators
            .take(CLEANUP_WORKERS)
            .forEach(BatchCoordinator<Int>::forceStop)
        awaitAtMost(
            fixture.cancellationsEntered,
            COORDINATOR_COUNT - CLEANUP_WORKERS,
        )

        val forceStarted = System.nanoTime()
        fixture.coordinators
            .drop(CLEANUP_WORKERS)
            .forEach(BatchCoordinator<Int>::forceStop)
        Duration.ofNanos(System.nanoTime() - forceStarted)
            .assert()
            .isLessThan(Duration.ofSeconds(1))
    }

    private fun assertLogicalDetach(coordinators: List<BatchCoordinator<Int>>) {
        coordinators.forEach { coordinator ->
            coordinator.areLanesDetached.assert().isTrue()
            coordinator.pendingItemCount.assert().isZero()
            coordinator.queuedItemCount.assert().isZero()
        }
        Thread.getAllStackTraces().keys
            .count { thread ->
                thread.isAlive &&
                    thread.name.startsWith("wow-batch-force-cleanup-")
            }.assert()
            .isLessThanOrEqualTo(CLEANUP_WORKERS)
    }

    private fun assertTerminalSignals(
        signals: List<CompletableFuture<Signal<Void>?>>,
    ) {
        signals.forEach { signal ->
            signal.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(BatchClosedException::class.java)
        }
    }

    private fun newFixture(): Fixture {
        val writersSubscribed = CountDownLatch(COORDINATOR_COUNT)
        val cancellationsEntered = CountDownLatch(COORDINATOR_COUNT)
        val releaseCancellations = CountDownLatch(1)
        val coordinators = List(COORDINATOR_COUNT) {
            coordinator(
                maxSize = 2,
                maxDelay = Duration.ofMillis(1),
                maxPendingItems = 2,
            ) {
                Mono.never<List<BatchItemResult>>()
                    .doOnSubscribe { writersSubscribed.countDown() }
                    .doOnCancel {
                        cancellationsEntered.countDown()
                        awaitIgnoringInterrupt(releaseCancellations)
                    }
            }
        }
        return Fixture(
            coordinators = coordinators,
            writersSubscribed = writersSubscribed,
            cancellationsEntered = cancellationsEntered,
            releaseCancellations = releaseCancellations,
        )
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Simulate an uncooperative user cancellation hook.
            }
        }
    }

    private fun awaitAtMost(
        latch: CountDownLatch,
        expectedCount: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (latch.count > expectedCount && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        (latch.count <= expectedCount).assert().isTrue()
    }

    private data class Fixture(
        val coordinators: List<BatchCoordinator<Int>>,
        val writersSubscribed: CountDownLatch,
        val cancellationsEntered: CountDownLatch,
        val releaseCancellations: CountDownLatch,
    )

    private companion object {
        const val CLEANUP_WORKERS: Int = 4
        const val CLEANUP_CAPACITY: Int = 20
        const val COORDINATOR_COUNT: Int = 24
    }
}
