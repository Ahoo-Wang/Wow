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

import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates bounded, non-blocking admission and graceful shutdown for
 * storage-independent reactive batches.
 *
 * Each internal batch lane owns buffering and serial writes. The internal admission controller owns the
 * global capacity shared by lanes, and the result dispatcher isolates
 * per-item subscriber callbacks.
 */
class BatchCoordinator<T : Any>(
    val name: String,
    val options: BatchOptions,
    private val writer: BatchWriter<T>,
    private val laneCount: Int = 1,
    private val laneSelector: (T) -> Int = { 0 },
    metrics: WowMetrics = WowMetrics.NONE,
) : GracefullyStoppable {
    init {
        require(name.isNotBlank()) {
            "name must not be blank."
        }
        require(laneCount > 0) {
            "laneCount must be greater than zero."
        }
    }

    private val batchMetrics = BatchMetrics(name, metrics)
    private val enabledMetrics = batchMetrics.takeIf(BatchMetrics::isEnabled)
    private val admission = BatchAdmission<T>(options.maxPendingItems, enabledMetrics)
    private val lifecycle = BatchLifecycle(name)
    private val processorTermination = CompletableFuture<Unit>()
    private val termination = CompletableFuture<Unit>()
    private val remainingLanes = AtomicInteger(laneCount)
    private val lanesDisposed = AtomicBoolean()
    private val batchScheduler = Schedulers.newSingle("$name-batch-window", true)
    private val resultDispatcher: BatchResultDispatcher
    private val lanes: Array<BatchLane<T>>

    init {
        resultDispatcher = BatchResultDispatcher(
            name = name,
            maxPendingItems = options.maxPendingItems,
            onTerminated = ::completeResultDrain,
        )
        lanes = Array(laneCount) { lane ->
            BatchLane(
                name = name,
                lane = lane,
                options = options,
                writer = writer,
                scheduler = batchScheduler,
                resultDispatcher = resultDispatcher,
                metrics = enabledMetrics,
                onError = { failLifecycle(it) },
                onComplete = ::completeLane,
            )
        }
    }

    fun submit(item: T): Mono<Void> = submit { item }

    @Suppress("TooGenericExceptionCaught")
    fun submit(itemFactory: () -> T): Mono<Void> {
        return Mono.defer {
            lifecycle.terminalErrorOrClosed()?.let {
                return@defer Mono.error(it)
            }
            val admissionRejection = admission.tryAcquire()
            if (admissionRejection != null) {
                lifecycle.terminalErrorOrClosed()?.let {
                    return@defer Mono.error(it)
                }
                batchMetrics.admissionRejected(admissionRejection)
                return@defer Mono.error(
                    BatchOverflowException(name, options.maxPendingItems)
                )
            }
            lifecycle.terminalErrorOrClosed()?.let {
                admission.releaseUntracked()
                return@defer Mono.error(it)
            }
            val (lane, request) = try {
                val item = itemFactory()
                selectLane(item) to admission.track(item)
            } catch (error: Throwable) {
                admission.releaseUntracked()
                Exceptions.throwIfFatal(error)
                return@defer Mono.error(error)
            }
            val emitResult = lifecycle.emitIfOpen {
                lanes[lane].emit(request)
            }
            if (emitResult.isFailure) {
                request.discardAdmission()
                return@defer Mono.error(enqueueFailure(emitResult))
            }
            request.result.asMono()
                .doOnCancel(request::cancel)
        }
    }

    override fun stopGracefully(): Mono<Void> {
        initiateClose()
        return Mono.fromFuture(termination, true).then()
    }

    override fun close() {
        close(DEFAULT_CLOSE_TIMEOUT)
    }

    fun close(timeout: Duration) {
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive."
        }
        initiateClose()
        val closeTermination = if (
            resultDispatcher.isDispatchingResult &&
            !lifecycle.isFailed
        ) {
            processorTermination
        } else {
            termination
        }
        try {
            closeTermination.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            failLifecycle(BatchCloseTimeoutException(name, timeout))?.let {
                throw it
            }
        } catch (error: InterruptedException) {
            closeInterrupted(error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun selectLane(item: T): Int {
        if (laneCount == 1) {
            return 0
        }
        val lane = laneSelector(item)
        check(lane in 0..<laneCount) {
            "Batch lane selector[$name] returned $lane outside [0, $laneCount)."
        }
        return lane
    }

    private fun dispatchPendingFailures(error: Throwable) {
        admission.pendingSnapshot()
            .filter {
                it.settleFailureIfUnsettled(error)
            }.forEach { item ->
                resultDispatcher.dispatch(item::signalSettled)
            }
    }

    private fun failPendingAfterResultDispatcherTermination(error: Throwable) {
        admission.pendingSnapshot().forEach { item ->
            if (item.settleFailureIfUnsettled(error)) {
                item.signalSettled()
            }
        }
    }

    private fun enqueueFailure(emitResult: Sinks.EmitResult): Throwable =
        emitResult.toBatchEnqueueError(
            coordinatorName = name,
            maxPendingItems = options.maxPendingItems,
            terminalFailure = lifecycle.failureCause,
        )

    private fun completeLane() {
        if (remainingLanes.decrementAndGet() == 0) {
            completeProcessor()
        }
    }

    private fun completeProcessor() {
        batchScheduler.dispose()
        when (val completion = lifecycle.processorCompleted()) {
            BatchLifecycle.ProcessorCompletion.DrainResults -> {
                processorTermination.complete(Unit)
            }

            BatchLifecycle.ProcessorCompletion.Closed -> {
                processorTermination.complete(Unit)
                termination.complete(Unit)
            }

            is BatchLifecycle.ProcessorCompletion.Failed -> {
                processorTermination.completeExceptionally(completion.cause)
                termination.completeExceptionally(completion.cause)
            }
        }
        resultDispatcher.shutdown()
    }

    private fun completeResultDrain() {
        when (val completion = lifecycle.resultDispatcherTerminated()) {
            BatchLifecycle.ResultDrainCompletion.Closed -> {
                batchMetrics.closeCompleted(failed = false)
                termination.complete(Unit)
            }

            is BatchLifecycle.ResultDrainCompletion.Failed -> {
                batchMetrics.closeCompleted(failed = true)
                disposeLanes()
                processorTermination.completeExceptionally(completion.cause)
                termination.completeExceptionally(completion.cause)
                failPendingAfterResultDispatcherTermination(completion.cause)
            }
        }
    }

    private fun initiateClose() {
        if (!lifecycle.initiateClose()) {
            return
        }
        batchMetrics.markCloseStarted()
        for (lane in lanes) {
            val emitResult = lane.complete()
            if (emitResult.isFailure && emitResult != Sinks.EmitResult.FAIL_TERMINATED) {
                failLifecycle(
                    IllegalStateException(
                        "Failed to close batch coordinator[$name]: $emitResult"
                    )
                )
                return
            }
        }
    }

    private fun closeInterrupted(error: InterruptedException): Nothing {
        Thread.currentThread().interrupt()
        val interruption = IllegalStateException(
            "Interrupted while closing batch coordinator[$name].",
            error,
        )
        throw failLifecycle(interruption) ?: interruption
    }

    private fun failLifecycle(error: Throwable): Throwable? {
        return when (val transition = lifecycle.fail(error)) {
            BatchLifecycle.FailureTransition.Closed -> {
                termination.complete(Unit)
                null
            }

            is BatchLifecycle.FailureTransition.Existing -> transition.cause
            is BatchLifecycle.FailureTransition.Installed -> {
                batchMetrics.coordinatorFailed()
                disposeLanes()
                dispatchPendingFailures(transition.cause)
                resultDispatcher.shutdown()
                processorTermination.completeExceptionally(transition.cause)
                batchMetrics.closeCompleted(failed = true)
                termination.completeExceptionally(transition.cause)
                transition.cause
            }
        }
    }

    private fun disposeLanes() {
        if (!lanesDisposed.compareAndSet(false, true)) {
            return
        }
        lanes.forEach(BatchLane<T>::dispose)
        // BatchLane.cancelOn schedules writer cancellation on this scheduler.
        // Dispose only after every preceding cancellation task has run.
        try {
            batchScheduler.schedule(batchScheduler::dispose)
        } catch (_: RejectedExecutionException) {
            batchScheduler.dispose()
        }
    }

    private companion object {
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
