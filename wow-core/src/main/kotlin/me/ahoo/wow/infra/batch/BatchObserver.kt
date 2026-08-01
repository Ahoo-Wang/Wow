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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives synchronous, storage-independent batch runtime observations.
 *
 * Implementations must be thread-safe, non-blocking, and avoid retaining an
 * observation beyond the callback. Callback failures are isolated from batch
 * submission, writing, and shutdown. Observations deliberately exclude item
 * values and [Throwable] instances.
 */
fun interface BatchObserver {
    fun onObservation(observation: BatchObservation)

    companion object {
        @JvmField
        val NOOP: BatchObserver = BatchObserver { }
    }
}

/**
 * Facts emitted by a [BatchCoordinator]. Duration fields use monotonic
 * nanoseconds and are always non-negative.
 */
sealed interface BatchObservation {
    val coordinatorName: String

    /**
     * Identifies one coordinator lifetime. Adapters may use this value to
     * correlate state, but must not expose it as a metric tag.
     */
    val coordinatorInstanceId: Long

    /** A sequenced snapshot after live or physical capacity changed. */
    data class CapacityChanged(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val capacity: BatchCapacitySnapshot,
    ) : BatchObservation

    /** A submission rejected before its item factory was invoked. */
    data class AdmissionRejected(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val reason: BatchAdmissionRejectionReason,
        val capacity: BatchCapacitySnapshot,
    ) : BatchObservation

    /** One queued request was claimed by a lane for writing. */
    data class RequestDequeued(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val lane: Int,
        val queueWaitNanos: Long,
    ) : BatchObservation

    /** A subscriber cancelled a request before a lane claimed it. */
    data class RequestCancelled(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
    ) : BatchObservation

    /** One physical writer invocation reached a terminal signal. */
    data class BatchWriteCompleted(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val lane: Int,
        val bufferedItems: Int,
        val writtenItems: Int,
        val windowType: BatchWindowType,
        val durationNanos: Long,
        val outcome: BatchWriteOutcome,
        val failedItems: Int,
        val failureType: String?,
    ) : BatchObservation

    /** The coordinator installed its first terminal failure. */
    data class CoordinatorFailed(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val failureType: String,
    ) : BatchObservation

    /** The coordinator accepted its first graceful-close request. */
    data class CloseStarted(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val capacity: BatchCapacitySnapshot,
    ) : BatchObservation

    /** Every lane stopped producing batches; result callbacks may remain. */
    data class ProcessorDrained(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val durationNanos: Long,
    ) : BatchObservation

    /** The close attempt reached its final successful or failed state. */
    data class CloseCompleted(
        override val coordinatorName: String,
        override val coordinatorInstanceId: Long,
        val durationNanos: Long,
        val outcome: BatchCloseOutcome,
        val failureType: String?,
    ) : BatchObservation
}

/**
 * Live requests and physical queue slots differ after queued cancellation.
 * [sequence] lets asynchronous adapters ignore an older snapshot delivered
 * after a newer concurrent capacity change.
 */
data class BatchCapacitySnapshot(
    val sequence: Long,
    val liveItems: Int,
    val queuedItems: Int,
    val liveHighWater: Int,
    val queuedHighWater: Int,
)

enum class BatchAdmissionRejectionReason {
    LIVE_ITEMS_EXHAUSTED,
    QUEUE_SLOTS_EXHAUSTED,
}

/**
 * `bufferTimeout` exposes whether a window was full, but not whether a partial
 * window was flushed by timeout or upstream close. This type intentionally
 * reports only the distinction the current pipeline can prove.
 */
enum class BatchWindowType {
    FULL,
    PARTIAL,
}

enum class BatchWriteOutcome {
    SUCCESS,
    ITEM_FAILURE,
    FAILED,
    CANCELLED,
}

enum class BatchCloseOutcome {
    SUCCESS,
    FAILED,
}

/** Dispatches to an immutable observer snapshot in iteration order. */
class CompositeBatchObserver(
    observers: Iterable<BatchObserver>,
) : BatchObserver {
    private val observers = observers.toList()

    override fun onObservation(observation: BatchObservation) {
        observers.forEach { observer -> observer.notifySafely(observation) }
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun BatchObserver.notifySafely(observation: BatchObservation) {
    try {
        onObservation(observation)
    } catch (failure: Exception) {
        observerLog.warn {
            "Batch observer [${javaClass.name}] failed while processing " +
                "observation [${observation.javaClass.name}] with failure type " +
                "[${failure.javaClass.name}]."
        }
    }
}

/**
 * Keeps observation bookkeeping outside the batching state machine. The NOOP
 * path avoids clocks, DTOs, atomics, and locks on the request hot path.
 */
internal class BatchObservationEmitter(
    private val coordinatorName: String,
    private val observer: BatchObserver,
    private val nanoTime: () -> Long,
) {
    private val enabled: Boolean = observer !== BatchObserver.NOOP
    private val coordinatorInstanceId = if (enabled) {
        coordinatorInstanceIds.incrementAndGet()
    } else {
        NO_INSTANCE_ID
    }
    val isEnabled: Boolean
        get() = enabled
    private val capacityLock = Any()
    private var capacitySequence: Long = 0
    private var liveItems: Int = 0
    private var queuedItems: Int = 0
    private var liveHighWater: Int = 0
    private var queuedHighWater: Int = 0
    private val closeStartedAt = AtomicLong(NOT_STARTED)
    private val processorDrained = AtomicBoolean()
    private val closeCompleted = AtomicBoolean()

    fun capacityChanged(
        liveDelta: Int,
        queueDelta: Int,
    ) {
        if (!enabled) {
            return
        }
        val snapshot = synchronized(capacityLock) {
            liveItems += liveDelta
            queuedItems += queueDelta
            liveHighWater = maxOf(liveHighWater, liveItems)
            queuedHighWater = maxOf(queuedHighWater, queuedItems)
            capacitySequence++
            capacitySnapshotUnsafe()
        }
        emit(
            BatchObservation.CapacityChanged(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                capacity = snapshot,
            )
        )
    }

    fun admissionRejected(reason: BatchAdmissionRejectionReason) {
        if (!enabled) {
            return
        }
        emit(
            BatchObservation.AdmissionRejected(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                reason = reason,
                capacity = capacitySnapshot(),
            )
        )
    }

    fun markEnqueued(): Long = if (enabled) nanoTime() else NOT_STARTED

    fun requestDequeued(
        lane: Int,
        enqueuedAt: Long,
    ) {
        if (!enabled) {
            return
        }
        emit(
            BatchObservation.RequestDequeued(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                lane = lane,
                queueWaitNanos = elapsedSince(enqueuedAt),
            )
        )
    }

    fun requestCancelled() {
        if (enabled) {
            emit(
                BatchObservation.RequestCancelled(
                    coordinatorName = coordinatorName,
                    coordinatorInstanceId = coordinatorInstanceId,
                )
            )
        }
    }

    fun batchWriteStarted(
        lane: Int,
        bufferedItems: Int,
        writtenItems: Int,
        windowType: BatchWindowType,
    ): BatchWriteObservation {
        check(enabled) { "Batch observation emitter is disabled." }
        val startedAt = nanoTime()
        val completed = AtomicBoolean()
        return BatchWriteObservation { outcome, failedItems, failure ->
            if (!completed.compareAndSet(false, true)) {
                return@BatchWriteObservation
            }
            emit(
                BatchObservation.BatchWriteCompleted(
                    coordinatorName = coordinatorName,
                    coordinatorInstanceId = coordinatorInstanceId,
                    lane = lane,
                    bufferedItems = bufferedItems,
                    writtenItems = writtenItems,
                    windowType = windowType,
                    durationNanos = elapsedSince(startedAt),
                    outcome = outcome,
                    failedItems = failedItems,
                    failureType = failure?.javaClass?.name,
                )
            )
        }
    }

    fun closeStarted() {
        if (!enabled) {
            return
        }
        val startedAt = nanoTime()
        if (!closeStartedAt.compareAndSet(NOT_STARTED, startedAt)) {
            return
        }
        emit(
            BatchObservation.CloseStarted(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                capacity = capacitySnapshot(),
            )
        )
    }

    fun coordinatorFailed(failure: Throwable) {
        if (!enabled) {
            return
        }
        emit(
            BatchObservation.CoordinatorFailed(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                failureType = failure.javaClass.name,
            )
        )
    }

    fun processorDrained() {
        val startedAt = closeStartedAt.get()
        if (
            !enabled ||
            startedAt == NOT_STARTED ||
            !processorDrained.compareAndSet(false, true)
        ) {
            return
        }
        emit(
            BatchObservation.ProcessorDrained(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                durationNanos = elapsedSince(startedAt),
            )
        )
    }

    fun closeCompleted(failure: Throwable?) {
        val startedAt = closeStartedAt.get()
        if (
            !enabled ||
            startedAt == NOT_STARTED ||
            !closeCompleted.compareAndSet(false, true)
        ) {
            return
        }
        emit(
            BatchObservation.CloseCompleted(
                coordinatorName = coordinatorName,
                coordinatorInstanceId = coordinatorInstanceId,
                durationNanos = elapsedSince(startedAt),
                outcome = if (failure == null) {
                    BatchCloseOutcome.SUCCESS
                } else {
                    BatchCloseOutcome.FAILED
                },
                failureType = failure?.javaClass?.name,
            )
        )
    }

    private fun capacitySnapshot(): BatchCapacitySnapshot =
        synchronized(capacityLock, ::capacitySnapshotUnsafe)

    private fun capacitySnapshotUnsafe(): BatchCapacitySnapshot =
        BatchCapacitySnapshot(
            sequence = capacitySequence,
            liveItems = liveItems,
            queuedItems = queuedItems,
            liveHighWater = liveHighWater,
            queuedHighWater = queuedHighWater,
        )

    private fun elapsedSince(startedAt: Long): Long =
        (nanoTime() - startedAt).coerceAtLeast(0)

    private fun emit(observation: BatchObservation) {
        observer.notifySafely(observation)
    }

    private companion object {
        const val NOT_STARTED: Long = Long.MIN_VALUE
        const val NO_INSTANCE_ID: Long = 0
        val coordinatorInstanceIds = AtomicLong()
    }
}

internal fun interface BatchWriteObservation {
    fun complete(
        outcome: BatchWriteOutcome,
        failedItems: Int,
        failure: Throwable?,
    )
}

private val observerLog = KotlinLogging.logger {}
