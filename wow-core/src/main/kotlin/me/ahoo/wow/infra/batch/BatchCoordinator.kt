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
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates bounded, non-blocking admission and graceful shutdown for
 * storage-independent reactive batches.
 *
 * Each internal batch lane owns buffering and serial writes. The internal admission controller owns the
 * global capacity shared by lanes. Equal keys map to the same serial lane;
 * different lanes may invoke the writer concurrently. This does not impose
 * item order within a native batch. The key selector must be stable and
 * non-blocking; single-lane coordinators do not invoke it.
 *
 * Accepted results are actively emitted only by owned result tasks. A terminal
 * signal stored before the subscription handshake finishes may subsequently
 * replay on the subscriber thread; caller-provided scheduling is also outside
 * this coordinator's control.
 */
class BatchCoordinator<T : Any>(
    val name: String,
    val options: BatchOptions,
    private val writer: BatchWriter<T>,
    private val keySelector: (T) -> Any = { Unit },
    metrics: WowMetrics = WowMetrics.NONE,
) : GracefullyStoppable {
    init {
        require(name.isNotBlank()) {
            "name must not be blank."
        }
    }

    private val laneCount = options.laneCount
    private val batchMetrics = BatchMetrics(name, metrics)
    private val enabledMetrics = batchMetrics.takeIf(BatchMetrics::isEnabled)
    private val admission = BatchAdmission<T>(options.maxPendingItems, enabledMetrics)
    private val lifecycle = BatchLifecycle(name)

    // Lock order: a lane's admission lock may be held while taking the lifecycle lock (a lane that completes or fails
    // synchronously), never the reverse. Never acquire the result lock while holding an admission or lifecycle lock,
    // or vice versa.
    private val resultLock = Any()
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
            lock = resultLock,
            onTerminated = ::completeResultDrain,
        )
        lanes = Array(laneCount) { lane ->
            BatchLane(
                name = name,
                lane = lane,
                options = options,
                writer = writer,
                scheduler = batchScheduler,
                settle = ::settleBatch,
                metrics = enabledMetrics,
                onError = { failLifecycle(it) },
                onComplete = ::completeLane,
            )
        }
    }

    fun submit(item: T): Mono<Void> = submit { item }

    /**
     * Reserves capacity before invoking the factory, once per subscription.
     * Only successful enqueue accepts a request. The factory runs on the
     * submitting thread and must be finite, non-blocking and free of storage I/O.
     * Closing does not wait for a factory that has not yet enqueued its item;
     * when that factory returns, its reservation is released and enqueue fails.
     */
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
            val emitResult = lanes[lane].emitIfOpen(lifecycle::isOpen) {
                admission.accept(request)
                request
            }
            if (emitResult.isFailure) {
                request.discardAdmission()
                return@defer Mono.error(enqueueFailure(emitResult))
            }
            request.result.asMono()
                .doOnCancel(request::cancel)
        }
    }

    /**
     * Closes admission and flushes remaining windows. Completion waits for
     * accepted writes and owned notification tasks, including their synchronous
     * callbacks, but not late signal replay or caller-scheduled asynchronous work.
     * Cancelling an observer does not cancel the shared shutdown process.
     */
    override fun stopGracefully(): Mono<Void> {
        initiateClose()
        return Mono.fromFuture(termination, true).then()
    }

    /** The failure [forceStop] installed; [close] after it returns quietly instead of throwing it. */
    private val forceStopCause = AtomicReference<Throwable>()
    private val forceStopCloseLogged = AtomicBoolean()

    /**
     * Closes admission and fails every accepted item that is not written yet with [BatchClosedException], without
     * waiting. A coordinator that already closed is not affected. A later [close] returns at once without throwing,
     * so an owner that closes after the runtime force-stopped it (Spring bean destruction) does not fail.
     */
    fun forceStop() {
        // Published before the failure, so a close that observes this failure also recognises it. Repeated force
        // stops reuse the first cause.
        forceStopCause.compareAndSet(null, BatchClosedException(name))
        failLifecycle(forceStopCause.get())
    }

    override fun close() {
        close(DEFAULT_CLOSE_TIMEOUT)
    }

    override fun stop() = close()

    override fun stop(timeout: Duration) = close(timeout)

    @Suppress("ThrowsCount")
    fun close(timeout: Duration) {
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive."
        }
        initiateClose()
        lifecycle.failureCause?.let { failure ->
            if (failure === forceStopCause.get()) {
                if (forceStopCloseLogged.compareAndSet(false, true)) {
                    log.info { "Batch coordinator[$name] was force stopped; unwritten items were failed." }
                }
                return
            }
            throw failure
        }
        val closeTermination = if (resultDispatcher.isDispatchingResult) {
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
        return Math.floorMod(keySelector(item).hashCode(), laneCount)
    }

    private fun settleBatch(requests: List<BatchRequest<T>>, outcomes: List<BatchItemResult>) {
        try {
            synchronized(resultLock) {
                requests.forEachIndexed { index, request ->
                    request.settle(outcomes[index])
                    resultDispatcher.publish(request)
                }
            }
        } catch (error: RejectedExecutionException) {
            failLifecycle(error)
        }
    }

    private fun dispatchPendingFailures(requests: List<BatchRequest<T>>, error: Throwable) {
        requests.forEach { request ->
            request.settleFailureIfUnsettled(error)
            resultDispatcher.publish(request)
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
        // Usually runs on the window thread, where dispose() would interrupt the thread that goes on
        // to complete termination and run shutdown observers. Every lane has completed, so no
        // scheduled work remains to cancel.
        batchScheduler.disposeGracefully().subscribe()
        val completion = lifecycle.processorCompleted()
        // A failure owner may still be publishing its accepted snapshot. It alone may seal it.
        val shutdown = completion !is BatchLifecycle.ProcessorCompletion.Failed && synchronized(resultLock) {
            resultDispatcher.seal()
        }
        when (completion) {
            is BatchLifecycle.ProcessorCompletion.Failed ->
                processorTermination.completeExceptionally(completion.cause)
            else -> processorTermination.complete(Unit)
        }
        if (shutdown) {
            resultDispatcher.shutdown()
        }
    }

    private fun completeResultDrain() {
        when (val completion = lifecycle.resultDispatcherTerminated()) {
            BatchLifecycle.ResultDrainCompletion.Closed -> {
                batchMetrics.closeCompleted(failed = false)
                termination.complete(Unit)
            }

            is BatchLifecycle.ResultDrainCompletion.Failed -> {
                batchMetrics.closeCompleted(failed = true)
                termination.completeExceptionally(completion.cause)
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
            BatchLifecycle.FailureTransition.Closed -> null
            is BatchLifecycle.FailureTransition.Existing -> transition.cause
            is BatchLifecycle.FailureTransition.Installed -> {
                // An admission that saw the lifecycle open has accepted its request once its lane lock is free.
                lanes.forEach(BatchLane<T>::awaitAdmissions)
                val pending = admission.pendingSnapshot()
                val shutdown = synchronized(resultLock) {
                    try {
                        dispatchPendingFailures(pending, transition.cause)
                    } catch (_: RejectedExecutionException) {
                        // A broken executor cannot guarantee delivery. Preserve the terminal failure.
                    }
                    resultDispatcher.seal()
                }
                batchMetrics.coordinatorFailed()
                disposeLanes()
                processorTermination.completeExceptionally(transition.cause)
                if (shutdown) {
                    resultDispatcher.shutdown()
                }
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
        private val log = KotlinLogging.logger {}
    }
}
