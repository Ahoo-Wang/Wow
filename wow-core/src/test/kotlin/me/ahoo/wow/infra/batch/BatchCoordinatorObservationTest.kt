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
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BatchCoordinatorObservationTest {
    @Test
    fun `successful full batch should report capacity queue wait and write completion`() {
        val clock = AtomicLong(100)
        val observer = RecordingBatchObserver()
        val coordinator = observedCoordinator(observer, clock) { items ->
            clock.set(200)
            Mono.just(items.map { BatchItemResult.Success })
        }

        try {
            Flux.merge(coordinator.submit(1), coordinator.submit(2))
                .then()
                .block(Duration.ofSeconds(1))
        } finally {
            coordinator.close(Duration.ofSeconds(1))
        }

        observer.events.filterIsInstance<BatchObservation.RequestDequeued>()
            .assert()
            .hasSize(2)
            .allMatch { it.lane == 0 && it.queueWaitNanos == 0L }
        observer.events.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .single()
            .let { completed ->
                completed.assert().isEqualTo(
                    BatchObservation.BatchWriteCompleted(
                        coordinatorName = "observed-test",
                        coordinatorInstanceId = completed.coordinatorInstanceId,
                        lane = 0,
                        bufferedItems = 2,
                        writtenItems = 2,
                        windowType = BatchWindowType.FULL,
                        durationNanos = 100,
                        outcome = BatchWriteOutcome.SUCCESS,
                        failedItems = 0,
                        failureType = null,
                    )
                )
            }
        observer.events.filterIsInstance<BatchObservation.CapacityChanged>()
            .maxOf { it.capacity.liveHighWater }
            .assert()
            .isEqualTo(2)
        val finalCapacity = observer.events.filterIsInstance<BatchObservation.CapacityChanged>()
            .last()
            .capacity
        finalCapacity.liveItems.assert().isZero()
        finalCapacity.queuedItems.assert().isZero()
    }

    @Test
    fun `partial close flush should not pretend to know timeout versus close cause`() {
        val observer = RecordingBatchObserver()
        val coordinator = observedCoordinator(
            observer = observer,
            maxSize = 4,
        ) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val result = coordinator.submit(1).toFuture()

        coordinator.close(Duration.ofSeconds(1))
        result.get(1, TimeUnit.SECONDS)

        observer.events.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .single()
            .windowType
            .assert()
            .isEqualTo(BatchWindowType.PARTIAL)
    }

    @Test
    fun `batch-local item and writer failures should have distinct outcomes`() {
        val observer = RecordingBatchObserver()
        val itemFailure = IllegalStateException("item failed")
        val writerFailure = IllegalArgumentException("writer failed")
        val coordinator = observedCoordinator(observer) { items ->
            if (items.first() == 1) {
                Mono.just(
                    listOf(
                        BatchItemResult.Success,
                        BatchItemResult.Failure(itemFailure),
                    )
                )
            } else {
                Mono.error(writerFailure)
            }
        }

        try {
            batchSignals(coordinator, 1, 2).block(Duration.ofSeconds(1))
            batchSignals(coordinator, 3, 4).block(Duration.ofSeconds(1))
        } finally {
            coordinator.close(Duration.ofSeconds(1))
        }

        val writes = observer.events.filterIsInstance<BatchObservation.BatchWriteCompleted>()
        writes.map(BatchObservation.BatchWriteCompleted::outcome)
            .assert()
            .containsExactly(
                BatchWriteOutcome.ITEM_FAILURE,
                BatchWriteOutcome.FAILED,
            )
        writes.map(BatchObservation.BatchWriteCompleted::failedItems)
            .assert()
            .containsExactly(1, 2)
        writes.last().failureType.assert().isEqualTo(writerFailure.javaClass.name)
        observer.events.filterIsInstance<BatchObservation.CoordinatorFailed>()
            .assert()
            .isEmpty()
    }

    @Test
    fun `keyed coordinator should expose stable lane ids`() {
        val observer = RecordingBatchObserver()
        val coordinator = KeyedBatchCoordinator<Int, Int>(
            name = "observed-keyed-test",
            options = options(
                maxSize = 2,
                maxDelay = Duration.ofHours(1),
                maxPendingItems = 4,
            ),
            laneCount = 2,
            observer = observer,
            keySelector = { it },
            writer = BatchWriter { items ->
                Mono.just(items.map { BatchItemResult.Success })
            },
        )

        try {
            Flux.just(0, 1, 2, 3)
                .flatMap(coordinator::submit, 4)
                .then()
                .block(Duration.ofSeconds(1))
        } finally {
            coordinator.close(Duration.ofSeconds(1))
        }

        observer.events.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .map(BatchObservation.BatchWriteCompleted::lane)
            .sorted()
            .assert()
            .containsExactly(0, 1)
    }

    @Test
    fun `overflow should distinguish live items from physical queue slots`() {
        val observer = RecordingBatchObserver()
        val liveCapacityCoordinator = observedCoordinator(
            observer = observer,
            maxSize = 2,
            maxPendingItems = 2,
        ) { Mono.never() }
        val firstLive = liveCapacityCoordinator.submit(1).materialize().toFuture()
        val secondLive = liveCapacityCoordinator.submit(2).materialize().toFuture()
        StepVerifier.create(liveCapacityCoordinator.submit(3))
            .expectError(BatchOverflowException::class.java)
            .verify()
        val liveCloseError = assertThrows<BatchCloseTimeoutException> {
            liveCapacityCoordinator.close(Duration.ofMillis(10))
        }
        firstLive.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(liveCloseError)
        secondLive.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(liveCloseError)

        val queueCapacityCoordinator = observedCoordinator(
            observer = observer,
            maxSize = 2,
            maxPendingItems = 3,
        ) { Mono.never() }
        val firstQueued = queueCapacityCoordinator.submit(1).materialize().toFuture()
        val secondQueued = queueCapacityCoordinator.submit(2).materialize().toFuture()
        queueCapacityCoordinator.submit(3).subscribe().dispose()
        queueCapacityCoordinator.submit(4).subscribe().dispose()
        queueCapacityCoordinator.submit(5).subscribe().dispose()
        StepVerifier.create(queueCapacityCoordinator.submit(6))
            .expectError(BatchOverflowException::class.java)
            .verify()

        val reasons = observer.events
            .filterIsInstance<BatchObservation.AdmissionRejected>()
            .map(BatchObservation.AdmissionRejected::reason)
        reasons.assert().containsExactly(
            BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED,
            BatchAdmissionRejectionReason.QUEUE_SLOTS_EXHAUSTED,
        )

        val queueCloseError = assertThrows<BatchCloseTimeoutException> {
            queueCapacityCoordinator.close(Duration.ofMillis(10))
        }
        firstQueued.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(queueCloseError)
        secondQueued.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(queueCloseError)
        observer.events.filterIsInstance<BatchObservation.CoordinatorFailed>()
            .assert()
            .hasSize(2)
            .allMatch {
                it.failureType == BatchCloseTimeoutException::class.java.name
            }
    }

    @Test
    fun `queued cancellation should be observed once without invoking the writer`() {
        val observer = RecordingBatchObserver()
        val coordinator = observedCoordinator(
            observer = observer,
            maxSize = 4,
        ) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val cancelled = coordinator.submit(1).subscribe()
        cancelled.dispose()

        coordinator.close(Duration.ofSeconds(1))

        observer.events.filterIsInstance<BatchObservation.RequestCancelled>()
            .assert()
            .hasSize(1)
        observer.events.filterIsInstance<BatchObservation.BatchWriteCompleted>()
            .assert()
            .isEmpty()
    }

    @Test
    fun `close should report processor and result drain separately`() {
        val observer = RecordingBatchObserver()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val coordinator = observedCoordinator(observer) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }
        val blockedResult = coordinator.submit(1)
            .doOnSuccess {
                callbackEntered.countDown()
                releaseCallback.await()
            }.subscribe()
        val independentResult = coordinator.submit(2).toFuture()

        try {
            callbackEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            val closeResult = coordinator.stopGracefully().toFuture()
            awaitObservation<BatchObservation.ProcessorDrained>(observer)
            observer.events.filterIsInstance<BatchObservation.CloseCompleted>()
                .assert()
                .isEmpty()

            releaseCallback.countDown()
            independentResult.get(1, TimeUnit.SECONDS)
            closeResult.get(1, TimeUnit.SECONDS)

            observer.events.filterIsInstance<BatchObservation.CloseStarted>()
                .assert()
                .hasSize(1)
            observer.events.filterIsInstance<BatchObservation.ProcessorDrained>()
                .assert()
                .hasSize(1)
            observer.events.filterIsInstance<BatchObservation.CloseCompleted>()
                .single()
                .outcome
                .assert()
                .isEqualTo(BatchCloseOutcome.SUCCESS)
        } finally {
            releaseCallback.countDown()
            blockedResult.dispose()
            coordinator.close()
        }
    }

    @Test
    fun `observer failure should not change batching or close results`() {
        val coordinator = observedCoordinator(
            observer = BatchObserver { throw IllegalStateException("observer failed") }
        ) { items ->
            Mono.just(items.map { BatchItemResult.Success })
        }

        StepVerifier.create(
            Flux.merge(coordinator.submit(1), coordinator.submit(2)).then()
        )
            .verifyComplete()
        coordinator.close(Duration.ofSeconds(1))
    }

    private fun observedCoordinator(
        observer: BatchObserver,
        clock: AtomicLong = AtomicLong(System.nanoTime()),
        maxSize: Int = 2,
        maxPendingItems: Int = 8,
        writer: (List<Int>) -> Mono<List<BatchItemResult>>,
    ): BatchCoordinator<Int> {
        return BatchCoordinator(
            name = "observed-test",
            options = options(
                maxSize = maxSize,
                maxDelay = Duration.ofHours(1),
                maxPendingItems = maxPendingItems,
            ),
            writer = BatchWriter(writer),
            laneCount = 1,
            laneSelector = { 0 },
            observer = observer,
            nanoTime = clock::get,
        )
    }

    private inline fun <reified T : BatchObservation> awaitObservation(
        observer: RecordingBatchObserver,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (
            observer.events.none { it is T } &&
            System.nanoTime() < deadline
        ) {
            Thread.onSpinWait()
        }
        observer.events.any { it is T }.assert().isTrue()
    }

    private class RecordingBatchObserver : BatchObserver {
        val events = CopyOnWriteArrayList<BatchObservation>()

        override fun onObservation(observation: BatchObservation) {
            events += observation
        }
    }
}
