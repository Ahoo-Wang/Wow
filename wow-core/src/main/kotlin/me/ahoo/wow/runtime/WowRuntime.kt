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

package me.ahoo.wow.runtime

import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import me.ahoo.wow.infra.lifecycle.publishTerminalSignal
import me.ahoo.wow.infra.lifecycle.subscribeTerminalSignal
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.runtime.internal.DefaultRuntimeExecutionResources
import me.ahoo.wow.runtime.internal.RuntimeCleanupDispatcher
import me.ahoo.wow.runtime.internal.RuntimeComponentGroup
import me.ahoo.wow.runtime.internal.RuntimeExecutionResources
import me.ahoo.wow.runtime.internal.SealableFailureAccumulator
import me.ahoo.wow.runtime.internal.ShutdownSubscriptionBoundary
import me.ahoo.wow.runtime.internal.toNanosExact
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level owner and lifecycle orchestrator for the complete Wow runtime.
 *
 * Startup uses a readiness barrier: every component is prepared before any
 * component starts processing. Shutdown first reaches global quiescence, then
 * stops components in reverse order under one shared deadline. A fatal runtime
 * component error initiates the same complete-runtime shutdown path.
 */
class WowRuntime private constructor(
    components: List<RuntimeComponent>,
    val shutdownTimeout: Duration,
    val shutdownQuietPeriod: Duration,
    private val executionResources: RuntimeExecutionResources,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: Unit,
) : GracefullyStoppable,
    ForceStoppable {
    constructor(
        components: List<RuntimeComponent>,
        shutdownTimeout: Duration,
        shutdownQuietPeriod: Duration,
    ) : this(
        components = components,
        shutdownTimeout = shutdownTimeout,
        shutdownQuietPeriod = shutdownQuietPeriod,
        executionResources = DefaultRuntimeExecutionResources,
        constructorMarker = Unit,
    )

    internal constructor(
        components: List<RuntimeComponent>,
        shutdownTimeout: Duration,
        shutdownQuietPeriod: Duration,
        executionResources: RuntimeExecutionResources,
    ) : this(
        components = components,
        shutdownTimeout = shutdownTimeout,
        shutdownQuietPeriod = shutdownQuietPeriod,
        executionResources = executionResources,
        constructorMarker = Unit,
    )

    private companion object {
        const val SHUTDOWN_QUEUE_CAPACITY: Int = 256
        const val SHUTDOWN_THREAD_TTL_SECONDS: Int = 60
        val SHUTDOWN_DEADLINE_SCHEDULER = Schedulers.newBoundedElastic(
            1,
            SHUTDOWN_QUEUE_CAPACITY,
            "wow-runtime-deadline",
            SHUTDOWN_THREAD_TTL_SECONDS,
            true,
        )
    }

    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        FORCE_STOPPING,
        STOPPED,
    }

    init {
        require(!shutdownTimeout.isZero && !shutdownTimeout.isNegative) {
            "shutdownTimeout must be positive."
        }
        require(!shutdownQuietPeriod.isNegative && shutdownQuietPeriod < shutdownTimeout) {
            "shutdownQuietPeriod must not be negative and must be shorter than shutdownTimeout."
        }
    }

    private val shutdownTimeoutNanos = shutdownTimeout.toNanosExact("shutdownTimeout")

    /**
     * Unmodifiable snapshot of the registered component topology.
     *
     * Lifecycle operations remain exclusively owned by this runtime; this
     * snapshot is exposed only for topology inspection.
     */
    val components: List<RuntimeComponent> =
        Collections.unmodifiableList(components.toList())

    private val lifecycleMonitor = Any()
    private val firstFailure = SealableFailureAccumulator()
    private val runtimeContext = DefaultRuntimeContext(
        shutdownQuietPeriod = shutdownQuietPeriod,
        scheduler = executionResources.quiescenceScheduler,
        failures = firstFailure,
    )
    private val componentGroup = RuntimeComponentGroup.claim(this.components) { error ->
        firstFailure.record(error)
    }
    private val terminationSink = Sinks.empty<Void>()
    private val rawTerminationSignal = terminationSink.asMono()

    /**
     * Hot, replayable completion of the runtime, including fatal errors.
     *
     * Each subscriber reserves bounded delivery capacity when it subscribes.
     * An over-capacity subscriber receives `RejectedExecutionException`
     * immediately on its subscription thread. Once admitted, terminal callbacks
     * are delivered off the runtime completion thread unless explicitly
     * cancelled, so an arbitrary observer cannot block [forceStop].
     */
    val terminationSignal: Mono<Void> =
        rawTerminationSignal.publishTerminalSignal(executionResources.terminationDispatcher)
    private val terminationControlClaimed = AtomicBoolean()
    private var runtimeFailureSubscription: Disposable? = null
    private var forceCleanupStarted = false
    private var shutdownOwner: ShutdownOwner? = null
    internal var shutdownDeadlineScheduler: Scheduler = SHUTDOWN_DEADLINE_SCHEDULER

    @Volatile
    private var state = State.NEW

    val isRunning: Boolean
        get() = state == State.RUNNING || state == State.STOPPING || state == State.FORCE_STOPPING

    /**
     * Exclusively claims the trusted termination control plane.
     *
     * The callback receives `null` for successful termination or the sealed
     * runtime failure otherwise. It runs on a dedicated bounded control
     * dispatcher that public [terminationSignal] observers cannot occupy.
     * Exactly one controller may be claimed for this one-shot runtime; disposing
     * the returned handle cancels delivery but does not make the claim reusable.
     *
     * The callback must return promptly and offload blocking work.
     */
    @Suppress("TooGenericExceptionCaught")
    fun claimTerminationControl(
        onTermination: (Throwable?) -> Unit,
    ): Disposable {
        check(terminationControlClaimed.compareAndSet(false, true)) {
            "WowRuntime termination control has already been claimed."
        }
        return try {
            rawTerminationSignal.subscribeTerminalSignal(
                dispatcher = executionResources.terminationControlDispatcher,
                onTermination = onTermination,
                rejectionMessage =
                "The process-wide Wow runtime termination control dispatcher is saturated.",
            )
        } catch (error: Throwable) {
            terminationControlClaimed.compareAndSet(true, false)
            throw error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun start() {
        synchronized(lifecycleMonitor) {
            check(state == State.NEW) {
                "WowRuntime can only be started once. Current state: $state."
            }
            state = State.STARTING
            runtimeFailureSubscription = runtimeContext.failureSignal.subscribe(
                {},
                ::handleRuntimeFailure,
            )
        }
        try {
            val prepared = componentGroup.prepare(
                runtimeContext = runtimeContext,
                admissionGate = ::admitComponentLifecycleAction,
                afterEach = ::ensureStartupContinues,
            )
            if (!prepared) {
                ensureStartupContinues()
            }
            val started = componentGroup.start(
                admissionGate = ::admitComponentLifecycleAction,
                afterEach = ::ensureStartupContinues,
            )
            if (!started) {
                ensureStartupContinues()
            }
            synchronized(lifecycleMonitor) {
                throwIfStartupFailed()
                ensureStarting()
                state = State.RUNNING
            }
        } catch (startFailure: Throwable) {
            Exceptions.throwIfFatal(startFailure)
            val cleanup = claimStartFailureCleanup(startFailure)
            if (cleanup == null) {
                throw resolveStartFailureAfterConcurrentShutdown(startFailure)
            }
            val cleanupFailure = cleanupAfterStartFailure(cleanup.owner)
            throw cleanup.primaryFailure.withCleanupFailure(cleanupFailure)
        }
    }

    /**
     * Preserves an already-recorded first failure when force-stop publishes
     * termination before an in-flight startup action returns.
     *
     * Once termination is published, its failure is sealed and must not be
     * mutated with a late suppressed exception. Before publication, a genuine
     * late startup failure can still be retained as suppressed evidence.
     */
    private fun resolveStartFailureAfterConcurrentShutdown(
        startFailure: Throwable,
    ): Throwable =
        synchronized(lifecycleMonitor) {
            val primaryFailure = currentFailure() ?: return@synchronized startFailure
            if (state != State.STOPPED && startFailure !is StartupCancelledException) {
                return@synchronized recordFailure(startFailure)
            }
            primaryFailure
        }

    private fun claimStartFailureCleanup(startFailure: Throwable): StartFailureCleanup? =
        synchronized(lifecycleMonitor) {
            when (state) {
                State.STARTING -> {
                    val primaryFailure = recordFailure(startFailure)
                    StartFailureCleanup(
                        primaryFailure,
                        newShutdownOwner(failOnRecordedFailure = false),
                    )
                }

                State.STOPPING -> {
                    val primaryFailure = if (startFailure is StartupCancelledException) {
                        currentFailure() ?: startFailure
                    } else {
                        recordFailure(startFailure)
                    }
                    StartFailureCleanup(
                        primaryFailure,
                        checkNotNull(shutdownOwner),
                    )
                }

                State.NEW,
                State.RUNNING,
                State.FORCE_STOPPING,
                State.STOPPED,
                -> null
            }
        }

    override fun stopGracefully(): Mono<Void> {
        var startupShutdown = false
        var completeWithoutStarting = false
        val owner = synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW -> {
                    state = State.STOPPED
                    completeWithoutStarting = true
                    null
                }

                State.RUNNING -> newShutdownOwner()

                State.STARTING -> {
                    startupShutdown = true
                    newShutdownOwner()
                }

                State.STOPPING,
                State.FORCE_STOPPING,
                State.STOPPED,
                -> null
            }
        }
        if (completeWithoutStarting) {
            terminationSink.tryEmitEmpty()
        }
        owner?.let {
            scheduleShutdownDeadline(it)
            if (startupShutdown) {
                Unit
            } else {
                subscribeShutdown(it)
            }
        }
        return terminationSignal
    }

    override fun forceStop() {
        forceStop(
            expectedOwner = null,
            triggeringFailure = null,
        )
    }

    private fun forceStop(
        expectedOwner: ShutdownOwner?,
        triggeringFailure: Throwable?,
    ) {
        var gracefulOwner: ShutdownOwner? = null
        val forceOwner = synchronized(lifecycleMonitor) {
            if (
                expectedOwner != null &&
                (shutdownOwner !== expectedOwner || state != State.STOPPING)
            ) {
                null
            } else if (state == State.STOPPED || forceCleanupStarted) {
                null
            } else {
                triggeringFailure?.let(::recordFailure)
                forceCleanupStarted = true
                state = State.FORCE_STOPPING
                gracefulOwner = shutdownOwner
                gracefulOwner?.markCancelled()
                ShutdownOwner(
                    deadlineNanos = System.nanoTime() + shutdownTimeoutNanos,
                ).also { owner ->
                    shutdownOwner = owner
                }
            }
        }
        if (forceOwner == null) {
            if (expectedOwner == null) {
                runtimeContext.forceClose()
            }
            return
        }
        runtimeContext.forceClose()
        gracefulOwner?.dispatchCancellation()
        val forceFailure = componentGroup.forceStop()
        forceFailure?.let(::recordFailure)
        completeShutdown(forceOwner, forceFailure)
    }

    private fun newShutdownOwner(
        failOnRecordedFailure: Boolean = true,
    ): ShutdownOwner {
        state = State.STOPPING
        return ShutdownOwner(
            failOnRecordedFailure = failOnRecordedFailure,
            deadlineNanos = System.nanoTime() + shutdownTimeoutNanos,
        ).also { owner ->
            shutdownOwner = owner
        }
    }

    private fun subscribeShutdown(owner: ShutdownOwner) {
        if (!owner.claimSubscription()) {
            return
        }
        val subscription = ShutdownSubscriptionBoundary(
            cleanupDispatcher = RuntimeCleanupDispatcher(executionResources::dispatchCleanup),
            onError = { error ->
                completeShutdown(owner, error)
            },
            onComplete = {
                completeShutdown(owner)
            },
        )
        if (!owner.attach(subscription)) {
            return
        }
        shutdownPipeline(owner).subscribe(subscription)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleShutdownDeadline(owner: ShutdownOwner) {
        if (!owner.claimDeadline()) {
            return
        }
        val deadlineAction = {
            forceStop(
                expectedOwner = owner,
                triggeringFailure = TimeoutException(
                    "WowRuntime shutdown timed out after $shutdownTimeout."
                ),
            )
        }
        val deadlineTask = try {
            shutdownDeadlineScheduler.schedule(
                deadlineAction,
                owner.remainingTimeoutNanos(),
                TimeUnit.NANOSECONDS,
            )
        } catch (schedulingFailure: Throwable) {
            Exceptions.throwIfFatal(schedulingFailure)
            forceStop(
                expectedOwner = owner,
                triggeringFailure = schedulingFailure,
            )
            return
        }
        owner.attachDeadline(deadlineTask)
    }

    private fun handleRuntimeFailure(failure: Throwable) {
        var startupFailure = false
        val owner = synchronized(lifecycleMonitor) {
            when (state) {
                State.STARTING -> {
                    startupFailure = true
                    recordFailure(failure)
                    newShutdownOwner(failOnRecordedFailure = false)
                }

                State.RUNNING -> {
                    recordFailure(failure)
                    newShutdownOwner()
                }

                State.STOPPING,
                State.FORCE_STOPPING,
                -> {
                    recordFailure(failure)
                    null
                }

                State.NEW,
                State.STOPPED,
                -> null
            }
        }
        owner?.let {
            scheduleShutdownDeadline(it)
            if (!startupFailure) {
                subscribeShutdown(it)
            }
        }
    }

    private fun shutdownPipeline(owner: ShutdownOwner): Mono<Void> {
        return Mono.defer(runtimeContext::quiesce)
            .doOnError { error ->
                recordFailure(error)
            }
            .then(
                componentGroup.stopGracefully(
                    shouldStop = { !owner.isCancelled },
                ),
            )
            .then(
                Mono.defer {
                    val terminalFailure = currentFailure()
                    if (owner.failOnRecordedFailure && terminalFailure != null) {
                        Mono.error<Void>(terminalFailure)
                    } else {
                        Mono.empty<Void>()
                    }
                },
            )
            .subscribeOn(executionResources.shutdownScheduler)
            .onErrorMap(::recordFailure)
            .onErrorResume(::forceStopAfterFailure)
    }

    private fun forceStopAfterFailure(primaryFailure: Throwable): Mono<Void> =
        Mono.defer {
            recordFailure(primaryFailure)
            val forceCleanupClaimed = synchronized(lifecycleMonitor) {
                if (forceCleanupStarted) {
                    false
                } else {
                    forceCleanupStarted = true
                    state = State.FORCE_STOPPING
                    true
                }
            }
            runtimeContext.forceClose()
            if (!forceCleanupClaimed) {
                return@defer rawTerminationSignal
            }
            val forceFailure = componentGroup.forceStop()
            forceFailure?.let(::recordFailure)
            Mono.error(currentFailure() ?: forceFailure ?: primaryFailure)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun cleanupAfterStartFailure(owner: ShutdownOwner): Throwable? {
        scheduleShutdownDeadline(owner)
        subscribeShutdown(owner)
        if (Schedulers.isInNonBlockingThread()) {
            return null
        }
        return try {
            rawTerminationSignal.toFuture().join()
            null
        } catch (completionFailure: CompletionException) {
            val cleanupFailure = completionFailure.cause ?: completionFailure
            Exceptions.throwIfFatal(cleanupFailure)
            cleanupFailure
        }
    }

    private fun ensureStartupContinues() {
        synchronized(lifecycleMonitor) {
            throwIfStartupFailed()
            ensureStarting()
        }
    }

    private fun admitComponentLifecycleAction(admission: () -> Boolean): Boolean =
        synchronized(lifecycleMonitor) {
            throwIfStartupFailed()
            ensureStarting()
            admission()
        }

    private fun ensureStarting() {
        if (state != State.STARTING) {
            throw StartupCancelledException(state.name)
        }
    }

    private fun completeShutdown(
        owner: ShutdownOwner,
        error: Throwable? = null,
    ) {
        val (failureSubscription, completionError) = synchronized(lifecycleMonitor) {
            if (shutdownOwner !== owner || state == State.STOPPED) {
                return
            }
            error?.let(::recordFailure)
            state = State.STOPPED
            val terminalError = firstFailure.seal()
            shutdownOwner = null
            runtimeFailureSubscription.also {
                runtimeFailureSubscription = null
            } to terminalError
        }
        failureSubscription?.dispose()
        owner.complete()
        if (completionError == null) {
            terminationSink.tryEmitEmpty()
        } else {
            terminationSink.tryEmitError(completionError)
        }
    }

    private fun throwIfStartupFailed() {
        currentFailure()?.let { throw it }
    }

    private fun currentFailure(): Throwable? = firstFailure.failure

    private fun recordFailure(error: Throwable): Throwable =
        firstFailure.record(error).primary

    private class ShutdownOwner(
        val failOnRecordedFailure: Boolean = true,
        private val deadlineNanos: Long,
    ) {
        private val cancelled = AtomicBoolean()
        private val cancellationDispatched = AtomicBoolean()
        private val subscriptionClaimed = AtomicBoolean()
        private val deadlineClaimed = AtomicBoolean()
        private val subscription = AtomicReference<ShutdownSubscriptionBoundary?>()
        private val deadlineTask = AtomicReference<Disposable?>()

        val isCancelled: Boolean
            get() = cancelled.get()

        fun claimSubscription(): Boolean = subscriptionClaimed.compareAndSet(false, true)

        fun claimDeadline(): Boolean = deadlineClaimed.compareAndSet(false, true)

        fun remainingTimeoutNanos(): Long =
            (deadlineNanos - System.nanoTime()).coerceAtLeast(1)

        fun attach(subscriptionBoundary: ShutdownSubscriptionBoundary): Boolean {
            if (!subscription.compareAndSet(null, subscriptionBoundary)) {
                subscriptionBoundary.detach()
                return false
            }
            if (cancelled.get()) {
                subscription.compareAndSet(subscriptionBoundary, null)
                subscriptionBoundary.detach()
                return false
            }
            return true
        }

        fun attachDeadline(disposable: Disposable) {
            if (!deadlineTask.compareAndSet(null, disposable)) {
                disposable.dispose()
                return
            }
            if (cancelled.get()) {
                deadlineTask.getAndSet(null)?.dispose()
            }
        }

        fun markCancelled(): Boolean = cancelled.compareAndSet(false, true)

        fun dispatchCancellation() {
            check(cancelled.get()) {
                "Shutdown cancellation must be marked before it is dispatched."
            }
            if (!cancellationDispatched.compareAndSet(false, true)) {
                return
            }
            deadlineTask.getAndSet(null)?.dispose()
            subscription.getAndSet(null)?.detach()
        }

        fun complete() {
            cancelled.set(true)
            deadlineTask.getAndSet(null)?.dispose()
            subscription.set(null)
        }
    }

    private class StartupCancelledException(
        private val cancelledState: String,
        cause: Throwable? = null,
    ) : IllegalStateException(
        "WowRuntime startup was cancelled by shutdown. Current state: $cancelledState.",
        cause,
    ) {
        fun withCleanupFailure(cleanupFailure: Throwable): StartupCancelledException =
            StartupCancelledException(cancelledState, cleanupFailure)
    }

    private fun Throwable.withCleanupFailure(cleanupFailure: Throwable?): Throwable =
        if (
            this is StartupCancelledException &&
            cleanupFailure != null &&
            cleanupFailure !== this
        ) {
            withCleanupFailure(cleanupFailure)
        } else {
            this
        }

    private data class StartFailureCleanup(
        val primaryFailure: Throwable,
        val owner: ShutdownOwner,
    )
}
