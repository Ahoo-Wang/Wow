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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import me.ahoo.wow.metrics.Metrics
import reactor.core.Exceptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

internal enum class BatchAdmissionRejectionReason(
    val metricValue: String,
) {
    LIVE_ITEMS_EXHAUSTED("live_items_exhausted"),
    QUEUE_SLOTS_EXHAUSTED("queue_slots_exhausted"),
}

internal enum class BatchWindowType(
    val metricValue: String,
) {
    FULL("full"),
    PARTIAL("partial"),
}

internal enum class BatchWriteOutcome(
    val metricValue: String,
) {
    SUCCESS("success"),
    ITEM_FAILURE("item_failure"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

/** Records bounded, storage-independent batch metrics through Micrometer. */
internal class BatchMetrics(
    coordinatorName: String,
    private val registry: MeterRegistry = MicrometerMetrics.globalRegistry,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    val isEnabled: Boolean = Metrics.enabled
    private val coordinatorTags = Tags.of(COORDINATOR_TAG, coordinatorName)
    private val closeStartedAt = AtomicLong(NOT_STARTED)
    private val closeCompleted = AtomicBoolean()

    fun admissionRejected(reason: BatchAdmissionRejectionReason) {
        if (!isEnabled) {
            return
        }
        recordSafely {
            registry.counter(
                ADMISSION_REJECTED,
                coordinatorTags.and(REASON_TAG, reason.metricValue),
            ).increment()
        }
    }

    fun markEnqueued(): Long = if (isEnabled) nanoTime() else NOT_STARTED

    fun requestDequeued(
        lane: Int,
        enqueuedAt: Long,
    ) {
        if (!isEnabled) {
            return
        }
        recordSafely {
            registry.timer(
                QUEUE_WAIT,
                coordinatorTags.and(LANE_TAG, lane.toString()),
            ).record(elapsedSince(enqueuedAt), TimeUnit.NANOSECONDS)
        }
    }

    fun batchWriteStarted(
        lane: Int,
        bufferedItems: Int,
        writtenItems: Int,
        windowType: BatchWindowType,
    ): BatchWriteMetrics {
        check(isEnabled) { "Batch metrics are disabled." }
        val startedAt = nanoTime()
        val completed = AtomicBoolean()
        return BatchWriteMetrics { outcome, failedItems ->
            if (!completed.compareAndSet(false, true)) {
                return@BatchWriteMetrics
            }
            recordBatchWrite(
                lane = lane,
                bufferedItems = bufferedItems,
                writtenItems = writtenItems,
                windowType = windowType,
                durationNanos = elapsedSince(startedAt),
                outcome = outcome,
                failedItems = failedItems,
            )
        }
    }

    fun markCloseStarted() {
        if (isEnabled) {
            closeStartedAt.compareAndSet(NOT_STARTED, nanoTime())
        }
    }

    fun coordinatorFailed() {
        if (!isEnabled) {
            return
        }
        recordSafely {
            registry.counter(COORDINATOR_FAILED, coordinatorTags).increment()
        }
    }

    fun closeCompleted(failed: Boolean) {
        val startedAt = closeStartedAt.get()
        if (
            !isEnabled ||
            startedAt == NOT_STARTED ||
            !closeCompleted.compareAndSet(false, true)
        ) {
            return
        }
        recordSafely {
            registry.timer(
                CLOSE,
                coordinatorTags.and(
                    OUTCOME_TAG,
                    if (failed) FAILED_VALUE else SUCCESS_VALUE,
                ),
            ).record(elapsedSince(startedAt), TimeUnit.NANOSECONDS)
        }
    }

    private fun recordBatchWrite(
        lane: Int,
        bufferedItems: Int,
        writtenItems: Int,
        windowType: BatchWindowType,
        durationNanos: Long,
        outcome: BatchWriteOutcome,
        failedItems: Int,
    ) {
        recordSafely {
            val tags = coordinatorTags
                .and(LANE_TAG, lane.toString())
                .and(WINDOW_TAG, windowType.metricValue)
                .and(OUTCOME_TAG, outcome.metricValue)
            registry.timer(BATCH_WRITE, tags)
                .record(durationNanos, TimeUnit.NANOSECONDS)
            registry.summary(BATCH_WRITE_ITEMS, tags.and(ITEM_KIND_TAG, BUFFERED_VALUE))
                .record(bufferedItems.toDouble())
            registry.summary(BATCH_WRITE_ITEMS, tags.and(ITEM_KIND_TAG, WRITTEN_VALUE))
                .record(writtenItems.toDouble())
            registry.summary(BATCH_WRITE_ITEMS, tags.and(ITEM_KIND_TAG, FAILED_VALUE))
                .record(failedItems.toDouble())
        }
    }

    private fun elapsedSince(startedAt: Long): Long =
        (nanoTime() - startedAt).coerceAtLeast(0)

    @Suppress("TooGenericExceptionCaught")
    private inline fun recordSafely(record: () -> Unit) {
        try {
            record()
        } catch (failure: Throwable) {
            Exceptions.throwIfFatal(failure)
            log.warn(failure) { "Failed to record batch metrics." }
        }
    }

    private companion object {
        const val ADMISSION_REJECTED = "wow.batch.admission.rejected"
        const val QUEUE_WAIT = "wow.batch.queue.wait"
        const val BATCH_WRITE = "wow.batch.write"
        const val BATCH_WRITE_ITEMS = "wow.batch.write.items"
        const val COORDINATOR_FAILED = "wow.batch.coordinator.failed"
        const val CLOSE = "wow.batch.close"

        const val COORDINATOR_TAG = "coordinator"
        const val LANE_TAG = "lane"
        const val REASON_TAG = "reason"
        const val WINDOW_TAG = "window"
        const val OUTCOME_TAG = "outcome"
        const val ITEM_KIND_TAG = "kind"

        const val SUCCESS_VALUE = "success"
        const val FAILED_VALUE = "failed"
        const val BUFFERED_VALUE = "buffered"
        const val WRITTEN_VALUE = "written"
        const val NOT_STARTED = Long.MIN_VALUE

        val log = KotlinLogging.logger {}
    }
}

internal fun interface BatchWriteMetrics {
    fun complete(
        outcome: BatchWriteOutcome,
        failedItems: Int,
    )
}
