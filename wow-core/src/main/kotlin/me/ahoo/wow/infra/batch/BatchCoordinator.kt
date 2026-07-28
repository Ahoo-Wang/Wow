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
import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import me.ahoo.wow.infra.lifecycle.forceStopAll
import me.ahoo.wow.infra.lifecycle.publishTerminalSignal
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates bounded, non-blocking admission and graceful shutdown for
 * storage-independent reactive batches.
 *
 * Each [BatchLane] owns buffering and serial writes. [BatchAdmission] owns the
 * global capacity shared by lanes, and [BatchResultDispatcher] isolates
 * per-item subscriber callbacks.
 */
class BatchCoordinator<T : Any> internal constructor(
    val name: String,
    val options: BatchOptions,
    private val writer: BatchWriter<T>,
    private val laneCount: Int,
    private val laneSelector: (T) -> Int,
    private val beforeResultDispatch: () -> Unit = {},
    private val forceLaneCleanupExecutor: Executor = FORCE_LANE_CLEANUP_EXECUTOR,
    private val detachedProcessorDisposer: (List<Disposable>) -> Throwable? =
        ::disposeDetachedProcessors,
    private val forceLaneCleanupFailureHandler: (Throwable) -> Unit = { failure ->
        log.warn(failure) {
            "Best-effort physical batch lane cancellation failed."
        }
    },
) : GracefullyStoppable,
    ForceStoppable {
    constructor(
        name: String,
        options: BatchOptions,
        writer: BatchWriter<T>,
    ) : this(
        name = name,
        options = options,
        writer = writer,
        laneCount = 1,
        laneSelector = { 0 },
    )

    init {
        require(name.isNotBlank()) {
            "name must not be blank."
        }
        require(laneCount > 0) {
            "laneCount must be greater than zero."
        }
    }

    private val admission = BatchAdmission<T>(options.maxPendingItems)
    private val lifecycle = BatchLifecycle(name)
    private val processorTermination = CompletableFuture<Unit>()
    private val resultDispatcherTermination = CompletableFuture<Unit>()
    private val termination = CompletableFuture<Unit>()
    private val terminationSignal = Mono.fromFuture(termination, true)
        .then()
        .publishTerminalSignal()
    private val remainingLanes = AtomicInteger(laneCount)
    private val lanesDisposed = AtomicBoolean()
    private val processorDisposalStarted = AtomicBoolean()
    private val forceResourceCleanupStarted = AtomicBoolean()
    private val terminalFailure = AtomicReference<Throwable?>()
    private val terminalMonitor = Any()
    private val forceCleanupMonitor = Any()
    private var sealedOutcome: TerminalOutcome? = null
    private var terminalPublicationClaimed = false
    private val batchScheduler = Schedulers.newSingle("$name-batch-window", true)
    private val resultDispatcher: BatchResultDispatcher
    private val lanes: Array<BatchLane<T>>

    internal val pendingItemCount: Int
        get() = admission.pendingCount

    internal val queuedItemCount: Int
        get() = admission.queuedCount

    internal val reservedItemCount: Int
        get() = admission.reservationCount

    internal val areLanesDetached: Boolean
        get() = lanesDisposed.get()

    internal val areResultCallbacksDetached: Boolean
        get() = lanes.all(BatchLane<T>::isResultCallbackDetached)

    init {
        resultDispatcher = BatchResultDispatcher(
            name = name,
            maxPendingItems = options.maxPendingItems,
            onTerminated = ::completeResultDrain,
        )
        lanes = Array(laneCount) {
            BatchLane(
                name = name,
                options = options,
                writer = writer,
                scheduler = batchScheduler,
                settleResults = ::settleResults,
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
            val reservation = admission.tryReserve()
            if (reservation == null) {
                lifecycle.terminalErrorOrClosed()?.let {
                    return@defer Mono.error(it)
                }
                return@defer Mono.error(
                    BatchOverflowException(name, options.maxPendingItems)
                )
            }
            lifecycle.terminalErrorOrClosed()?.let {
                reservation.release()
                return@defer Mono.error(it)
            }
            val (lane, item) = try {
                val item = itemFactory()
                selectLane(item) to item
            } catch (error: Throwable) {
                reservation.release()
                Exceptions.throwIfFatal(error)
                return@defer Mono.error(error)
            }
            val request = reservation.track(item)
                ?: return@defer Mono.error(
                    lifecycle.terminalErrorOrClosed() ?: BatchClosedException(name)
                )
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

    /**
     * Stops intake and returns an asynchronously isolated terminal signal.
     *
     * Terminal observers share a process-wide bounded dispatcher. A subscription
     * beyond that capacity fails fast with [RejectedExecutionException].
     */
    override fun stopGracefully(): Mono<Void> {
        initiateClose()
        return terminationSignal
    }

    override fun forceStop() {
        if (failLifecycle(BatchClosedException(name), force = true) == null) {
            resultDispatcher.forceShutdown()
        }
    }

    internal fun reportFailure(error: Throwable) {
        failLifecycle(error)
    }

    override fun close() {
        close(DEFAULT_CLOSE_TIMEOUT)
    }

    override fun stop(timeout: Duration) {
        close(timeout)
    }

    fun close(timeout: Duration) {
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive."
        }
        initiateClose()
        val closeTermination = if (resultDispatcher.isDispatchingResult) {
            processorTermination
        } else {
            termination
        }
        try {
            closeTermination.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            failLifecycle(
                BatchCloseTimeoutException(name, timeout),
                force = true,
                interruptResultCallbacks = false,
            )?.let {
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

    private fun preparePendingFailures(
        error: Throwable,
    ): List<BatchResultDispatcher.PreparedSignal> {
        val preparedSignals = admission.pendingSnapshot()
            .mapNotNull { request ->
                if (request.settleFailureIfUnsettled(error)) {
                    resultDispatcher.prepareDispatch(request::signalSettled)
                } else {
                    null
                }
            }
        admission.discardCancelledQueued()
        return preparedSignals
    }

    private fun settleResults(
        requests: List<BatchRequest<T>>,
        outcomes: List<BatchItemResult>,
    ) {
        val preparedSignals = synchronized(terminalMonitor) {
            if (terminalFailure.get() != null || sealedOutcome != null) {
                return
            }
            val settledRequests = requests.zip(outcomes)
                .mapNotNull { (request, outcome) ->
                    if (request.settle(outcome)) request else null
                }
            if (settledRequests.isNotEmpty()) {
                beforeResultDispatch()
            }
            settledRequests.map { request ->
                resultDispatcher.prepareDispatch(request::signalSettled)
            }
        }
        preparedSignals.forEach { preparedSignal ->
            check(preparedSignal.accepted) {
                "Batch result dispatcher[$name] closed during a protected result handoff."
            }
            preparedSignal.startFallbackIfNeeded()
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
        when (val completion = lifecycle.processorCompleted()) {
            BatchLifecycle.ProcessorCompletion.DrainResults -> {
                disposeProcessor()
            }

            BatchLifecycle.ProcessorCompletion.Closed -> {
                disposeProcessor()
            }

            is BatchLifecycle.ProcessorCompletion.Failed -> {
                disposeProcessor(completion.cause)
            }
        }
        shutdownResultDispatcher()
    }

    private fun completeResultDrain() {
        when (val completion = lifecycle.resultDispatcherTerminated()) {
            BatchLifecycle.ResultDrainCompletion.Closed -> {
                resultDispatcherTermination.complete(Unit)
                tryCompleteTermination()
            }

            is BatchLifecycle.ResultDrainCompletion.Failed -> {
                disposeProcessor(completion.cause)
                resultDispatcherTermination.complete(Unit)
                tryCompleteTermination()
            }
        }
    }

    private fun initiateClose() {
        if (!lifecycle.initiateClose()) {
            return
        }
        admission.releaseReservations()
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
        throw failLifecycle(
            interruption,
            force = true,
            interruptResultCallbacks = false,
        ) ?: interruption
    }

    private fun failLifecycle(
        error: Throwable,
        force: Boolean = false,
        interruptResultCallbacks: Boolean = force,
    ): Throwable? {
        val plan = synchronized(terminalMonitor) {
            if (sealedOutcome != null) {
                return null
            }
            val transition = lifecycle.fail(error)
            admission.releaseReservations()
            val primaryFailure = when (transition) {
                BatchLifecycle.FailureTransition.Closed -> error
                is BatchLifecycle.FailureTransition.Existing -> transition.cause
                is BatchLifecycle.FailureTransition.Installed -> transition.cause
            }
            val primaryInstalled = terminalFailure.compareAndSet(null, primaryFailure)
            val preparedSignals =
                if (primaryInstalled && !force) {
                    preparePendingFailures(primaryFailure)
                } else {
                    emptyList()
                }
            FailurePlan(
                primaryFailure = checkNotNull(terminalFailure.get()),
                primaryInstalled = primaryInstalled,
                preparedSignals = preparedSignals,
            )
        }
        plan.preparedSignals.forEach { preparedSignal ->
            check(preparedSignal.accepted) {
                "Batch result dispatcher[$name] closed during a protected failure handoff."
            }
            preparedSignal.startFallbackIfNeeded()
        }
        if (force) {
            forceCleanup(plan.primaryFailure, interruptResultCallbacks)
        } else if (plan.primaryInstalled) {
            disposeProcessor(plan.primaryFailure)
            shutdownResultDispatcher()
        }
        return plan.primaryFailure
    }

    @Suppress("TooGenericExceptionCaught")
    private fun forceCleanup(
        error: Throwable,
        interruptResultCallbacks: Boolean,
    ) {
        synchronized(forceCleanupMonitor) {
            val forcedSignals = detachPending(
                error = error,
                preserveSettledResults = !interruptResultCallbacks,
            )
            if (forceResourceCleanupStarted.compareAndSet(false, true)) {
                processorDisposalStarted.set(true)
                val detachedProcessors = detachLanes(detachResultDispatcher = true)
                try {
                    batchScheduler.dispose()
                } catch (cleanupFailure: Throwable) {
                    Exceptions.throwIfFatal(cleanupFailure)
                    reportCleanupFailure(cleanupFailure)
                }
                processorTermination.completeExceptionally(error)
                tryCompleteTermination()
                dispatchForceLaneCleanup(detachedProcessors)
            }
            if (interruptResultCallbacks) {
                resultDispatcher.forceShutdown(forcedSignals)
            } else {
                resultDispatcher.shutdown(forcedSignals)
            }
        }
    }

    private fun detachPending(
        error: Throwable,
        preserveSettledResults: Boolean,
    ): List<() -> Unit> =
        admission.ownedSnapshot().mapNotNull { request ->
            if (preserveSettledResults) {
                request.forceDetachIfUnsettled(error)
            } else {
                request.forceDetach(error)
            }
        }

    private fun disposeProcessor(error: Throwable? = null) {
        if (!processorDisposalStarted.compareAndSet(false, true)) {
            return
        }
        disposeLanes()
        batchScheduler.disposeGracefully().subscribe(
            {},
            { disposalFailure ->
                completeProcessorDisposal(error, disposalFailure)
            },
            {
                completeProcessorDisposal(error)
            },
        )
    }

    private fun completeProcessorDisposal(
        primaryFailure: Throwable?,
        disposalFailure: Throwable? = null,
    ) {
        val recordedFailure = primaryFailure ?: terminalFailure.get() ?: lifecycle.failureCause
        val terminalFailure = when {
            recordedFailure == null && disposalFailure == null -> null
            recordedFailure == null -> installCleanupFailure(disposalFailure!!)
            disposalFailure == null || disposalFailure === recordedFailure -> recordedFailure
            else -> recordedFailure.also {
                reportCleanupFailure(disposalFailure)
            }
        }
        if (terminalFailure == null) {
            processorTermination.complete(Unit)
        } else {
            processorTermination.completeExceptionally(terminalFailure)
        }
        tryCompleteTermination()
    }

    private fun installCleanupFailure(cleanupFailure: Throwable): Throwable =
        synchronized(terminalMonitor) {
            val failure = when (val transition = lifecycle.fail(cleanupFailure)) {
                BatchLifecycle.FailureTransition.Closed -> cleanupFailure
                is BatchLifecycle.FailureTransition.Existing -> transition.cause
                is BatchLifecycle.FailureTransition.Installed -> transition.cause
            }
            terminalFailure.compareAndSet(null, failure)
            checkNotNull(terminalFailure.get())
        }

    private fun disposeLanes() {
        detachLanes(detachResultDispatcher = false).forEach(Disposable::dispose)
    }

    private fun detachLanes(detachResultDispatcher: Boolean): List<Disposable> {
        lanes.forEach { lane ->
            lane.detachCallbacks(detachResultDispatcher)
        }
        if (!lanesDisposed.compareAndSet(false, true)) {
            return emptyList()
        }
        return lanes.mapNotNull { lane ->
            lane.detachProcessor()
        }
    }

    private fun tryCompleteTermination() {
        if (!processorTermination.isDone || !resultDispatcherTermination.isDone) {
            return
        }
        val outcome = synchronized(terminalMonitor) {
            if (
                !processorTermination.isDone ||
                !resultDispatcherTermination.isDone ||
                terminalPublicationClaimed
            ) {
                return
            }
            val terminalOutcome = sealedOutcome
                ?: (
                    terminalFailure.get()
                        ?.let(TerminalOutcome::Failure)
                        ?: lifecycle.failureCause
                            ?.let(TerminalOutcome::Failure)
                        ?: TerminalOutcome.Success
                    ).also { outcome ->
                    sealedOutcome = outcome
                }
            terminalPublicationClaimed = true
            terminalOutcome
        }
        when (outcome) {
            TerminalOutcome.Success -> termination.complete(Unit)
            is TerminalOutcome.Failure -> termination.completeExceptionally(outcome.cause)
        }
    }

    private fun shutdownResultDispatcher() {
        resultDispatcher.shutdown()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun reportCleanupFailure(cleanupFailure: Throwable) {
        try {
            forceLaneCleanupFailureHandler(cleanupFailure)
        } catch (reportingFailure: Throwable) {
            Exceptions.throwIfFatal(reportingFailure)
            log.warn(reportingFailure) {
                "Failed to report batch cleanup failure for coordinator[$name]."
            }
        }
    }

    private fun dispatchForceLaneCleanup(
        detachedProcessors: List<Disposable>,
    ) {
        if (detachedProcessors.isEmpty()) {
            return
        }
        try {
            forceLaneCleanupExecutor.execute {
                detachedProcessorDisposer(detachedProcessors)
                    ?.let(::reportCleanupFailure)
            }
        } catch (_: RejectedExecutionException) {
            log.warn {
                "Dropped best-effort physical lane cancellation for batch " +
                    "coordinator[$name] because the bounded force-cleanup " +
                    "executor is saturated. Logical ownership was already detached."
            }
        }
    }

    private companion object {
        const val FORCE_LANE_CLEANUP_THREADS: Int = 4
        const val FORCE_LANE_CLEANUP_QUEUE_CAPACITY: Int = 16
        val log = KotlinLogging.logger {}
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
        val FORCE_LANE_CLEANUP_THREAD_SEQUENCE = AtomicInteger()
        val FORCE_LANE_CLEANUP_EXECUTOR = ThreadPoolExecutor(
            FORCE_LANE_CLEANUP_THREADS,
            FORCE_LANE_CLEANUP_THREADS,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(FORCE_LANE_CLEANUP_QUEUE_CAPACITY),
            { runnable ->
                Thread(
                    runnable,
                    "wow-batch-force-cleanup-" +
                        FORCE_LANE_CLEANUP_THREAD_SEQUENCE.incrementAndGet(),
                ).apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )

        fun disposeDetachedProcessors(
            detachedProcessors: List<Disposable>,
        ): Throwable? =
            forceStopAll(
                forceActions = detachedProcessors.map { processor ->
                    processor::dispose
                },
                initialFailure = null,
            )
    }

    private sealed interface TerminalOutcome {
        data object Success : TerminalOutcome

        data class Failure(val cause: Throwable) : TerminalOutcome
    }

    private data class FailurePlan(
        val primaryFailure: Throwable,
        val primaryInstalled: Boolean,
        val preparedSignals: List<BatchResultDispatcher.PreparedSignal>,
    )
}
