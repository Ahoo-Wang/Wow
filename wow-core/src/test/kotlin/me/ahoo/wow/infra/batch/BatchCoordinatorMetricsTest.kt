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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class BatchCoordinatorMetricsTest {
    @Test
    fun `successful full batch should record queue write item and close metrics`() =
        withMeterRegistry { registry ->
            val name = "metrics-success"
            val coordinator = metricsCoordinator(name) { items ->
                Mono.just(items.map { BatchItemResult.Success })
            }

            try {
                Flux.merge(coordinator.submit(1), coordinator.submit(2))
                    .then()
                    .block(Duration.ofSeconds(1))
            } finally {
                coordinator.close(Duration.ofSeconds(1))
            }

            registry.timerCount(
                "wow.batch.queue.wait",
                "coordinator",
                name,
                "lane",
                "0",
            ).assert().isEqualTo(2)
            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "success",
            ).assert().isEqualTo(1)
            registry.summaryTotal(
                "wow.batch.write.items",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "success",
                "kind", "buffered",
            ).assert().isEqualTo(2.0)
            registry.summaryTotal(
                "wow.batch.write.items",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "success",
                "kind", "written",
            ).assert().isEqualTo(2.0)
            registry.summaryTotal(
                "wow.batch.write.items",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "success",
                "kind", "failed",
            ).assert().isEqualTo(0.0)
            registry.timerCount(
                "wow.batch.close",
                "coordinator",
                name,
                "outcome",
                "success",
            ).assert().isEqualTo(1)
        }

    @Test
    fun `partial close flush should record a partial write`() =
        withMeterRegistry { registry ->
            val name = "metrics-partial"
            val coordinator = metricsCoordinator(name, maxSize = 4) { items ->
                Mono.just(items.map { BatchItemResult.Success })
            }
            val result = coordinator.submit(1).toFuture()

            coordinator.close(Duration.ofSeconds(1))
            result.get(1, TimeUnit.SECONDS)

            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "0",
                "window", "partial",
                "outcome", "success",
            ).assert().isEqualTo(1)
        }

    @Test
    fun `item and writer failures should record distinct write outcomes`() =
        withMeterRegistry { registry ->
            val name = "metrics-write-failures"
            val itemFailure = IllegalStateException("item failed")
            val writerFailure = IllegalArgumentException("writer failed")
            val coordinator = metricsCoordinator(name) { items ->
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

            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "item_failure",
            ).assert().isEqualTo(1)
            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "failed",
            ).assert().isEqualTo(1)
            registry.summaryTotal(
                "wow.batch.write.items",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "item_failure",
                "kind", "failed",
            ).assert().isEqualTo(1.0)
            registry.summaryTotal(
                "wow.batch.write.items",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "failed",
                "kind", "failed",
            ).assert().isEqualTo(2.0)
            registry.counterCount(
                "wow.batch.coordinator.failed",
                "coordinator",
                name,
            ).assert().isEqualTo(0.0)
        }

    @Test
    fun `keyed coordinator should tag physical writes with stable lanes`() =
        withMeterRegistry { registry ->
            val name = "metrics-keyed"
            val coordinator = KeyedBatchCoordinator<Int, Int>(
                name = name,
                options = options(
                    maxSize = 2,
                    maxDelay = Duration.ofHours(1),
                    maxPendingItems = 4,
                ),
                laneCount = 2,
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

            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "0",
                "window", "full",
                "outcome", "success",
            ).assert().isEqualTo(1)
            registry.timerCount(
                "wow.batch.write",
                "coordinator", name,
                "lane", "1",
                "window", "full",
                "outcome", "success",
            ).assert().isEqualTo(1)
        }

    @Test
    fun `live item overflow and terminal failure should record bounded outcomes`() =
        withMeterRegistry { registry ->
            val liveName = "metrics-live-overflow"
            val liveCoordinator = metricsCoordinator(
                name = liveName,
                maxPendingItems = 2,
            ) { Mono.never() }
            val firstLive = liveCoordinator.submit(1).materialize().toFuture()
            val secondLive = liveCoordinator.submit(2).materialize().toFuture()

            StepVerifier.create(liveCoordinator.submit(3))
                .expectError(BatchOverflowException::class.java)
                .verify()
            val liveCloseError = assertThrows<BatchCloseTimeoutException> {
                liveCoordinator.close(Duration.ofMillis(10))
            }
            firstLive.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(liveCloseError)
            secondLive.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(liveCloseError)

            registry.counterCount(
                "wow.batch.admission.rejected",
                "coordinator",
                liveName,
                "reason",
                "live_items_exhausted",
            ).assert().isEqualTo(1.0)
            registry.counterCount(
                "wow.batch.coordinator.failed",
                "coordinator",
                liveName,
            ).assert().isEqualTo(1.0)
            registry.timerCount(
                "wow.batch.close",
                "coordinator",
                liveName,
                "outcome",
                "failed",
            ).assert().isEqualTo(1)
        }

    @Test
    fun `queue slot overflow should record its bounded reason`() =
        withMeterRegistry { registry ->
            val queueName = "metrics-queue-overflow"
            val firstBatchStarted = CountDownLatch(1)
            val queueCoordinator = metricsCoordinator(
                name = queueName,
                maxPendingItems = 3,
            ) {
                firstBatchStarted.countDown()
                Mono.never()
            }
            val firstQueued = queueCoordinator.submit(1).materialize().toFuture()
            val secondQueued = queueCoordinator.submit(2).materialize().toFuture()
            firstBatchStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            queueCoordinator.submit(3).subscribe().dispose()
            queueCoordinator.submit(4).subscribe().dispose()
            queueCoordinator.submit(5).subscribe().dispose()

            StepVerifier.create(queueCoordinator.submit(6))
                .expectError(BatchOverflowException::class.java)
                .verify()
            val queueCloseError = assertThrows<BatchCloseTimeoutException> {
                queueCoordinator.close(Duration.ofMillis(10))
            }
            firstQueued.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(queueCloseError)
            secondQueued.get(1, TimeUnit.SECONDS)!!.throwable.assert().isSameAs(queueCloseError)

            registry.counterCount(
                "wow.batch.admission.rejected",
                "coordinator",
                queueName,
                "reason",
                "queue_slots_exhausted",
            ).assert().isEqualTo(1.0)
        }

    @Test
    fun `queued cancellation should not record a physical write`() =
        withMeterRegistry { registry ->
            val name = "metrics-cancelled"
            val coordinator = metricsCoordinator(name, maxSize = 4) { items ->
                Mono.just(items.map { BatchItemResult.Success })
            }
            coordinator.submit(1).subscribe().dispose()

            coordinator.close(Duration.ofSeconds(1))

            registry.find("wow.batch.write")
                .tag("coordinator", name)
                .timers()
                .assert()
                .isEmpty()
        }

    @Test
    fun `close metric should wait for result drain`() =
        withMeterRegistry { registry ->
            val name = "metrics-result-drain"
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val coordinator = metricsCoordinator(name) { items ->
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
                closeResult.isDone.assert().isFalse()
                registry.find("wow.batch.close")
                    .tag("coordinator", name)
                    .timer()
                    .assert()
                    .isNull()

                releaseCallback.countDown()
                independentResult.get(1, TimeUnit.SECONDS)
                closeResult.get(1, TimeUnit.SECONDS)

                registry.timerCount(
                    "wow.batch.close",
                    "coordinator",
                    name,
                    "outcome",
                    "success",
                ).assert().isEqualTo(1)
            } finally {
                releaseCallback.countDown()
                blockedResult.dispose()
                coordinator.close()
            }
        }

    private fun metricsCoordinator(
        name: String,
        maxSize: Int = 2,
        maxPendingItems: Int = 8,
        writer: (List<Int>) -> Mono<List<BatchItemResult>>,
    ): BatchCoordinator<Int> =
        BatchCoordinator(
            name = name,
            options = options(
                maxSize = maxSize,
                maxDelay = Duration.ofHours(1),
                maxPendingItems = maxPendingItems,
            ),
            writer = BatchWriter(writer),
        )

    private fun withMeterRegistry(block: (SimpleMeterRegistry) -> Unit) {
        val registry = SimpleMeterRegistry()
        MicrometerMetrics.addRegistry(registry)
        try {
            block(registry)
        } finally {
            MicrometerMetrics.removeRegistry(registry)
            registry.close()
        }
    }

    private fun SimpleMeterRegistry.timerCount(
        name: String,
        vararg tags: String,
    ): Long = get(name).tags(*tags).timer().count()

    private fun SimpleMeterRegistry.summaryTotal(
        name: String,
        vararg tags: String,
    ): Double = get(name).tags(*tags).summary().totalAmount()

    private fun SimpleMeterRegistry.counterCount(
        name: String,
        vararg tags: String,
    ): Double = find(name).tags(*tags).counter()?.count() ?: 0.0
}
