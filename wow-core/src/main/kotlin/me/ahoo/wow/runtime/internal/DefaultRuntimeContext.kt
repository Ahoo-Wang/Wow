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

package me.ahoo.wow.runtime.internal

import me.ahoo.wow.runtime.RuntimeActivity
import me.ahoo.wow.runtime.RuntimeContext
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks complete-runtime activity and detects a stable idle shutdown boundary.
 *
 * Quiescing continues to admit work while activity is present or while the
 * configured quiet period is still open. Each new operation resets that period.
 * Once the runtime remains idle for the complete period, admission closes and
 * every registered dispatcher intake is stopped before quiescence completes.
 */
internal class DefaultRuntimeContext(
    private val shutdownQuietPeriod: Duration = Duration.ZERO,
    private val scheduler: Scheduler = Schedulers.parallel(),
    private val closeExecutor: Executor? = null,
    private val failures: SealableFailureAccumulator = SealableFailureAccumulator(),
) : RuntimeContext {
    companion object {
        private const val QUIESCING_MASK = Long.MIN_VALUE
        private const val CLOSED_MASK = 1L shl 62
        private const val ACTIVE_MASK = CLOSED_MASK - 1
    }

    init {
        require(!shutdownQuietPeriod.isNegative) {
            "shutdownQuietPeriod must not be negative."
        }
    }

    private val shutdownQuietPeriodNanos =
        shutdownQuietPeriod.toNanosExact("shutdownQuietPeriod")
    private val state = AtomicLong()
    private val activityVersion = AtomicLong()
    private val quietPeriodTask = AtomicReference<Disposable?>()
    private val quiescentSink = Sinks.empty<Void>()
    private val failureSink = Sinks.empty<Void>()
    private val closeActions = CopyOnWriteArrayList<CloseAction>()
    private val closeMonitor = Any()
    private val closeActionsStarted = AtomicBoolean()
    private val forceCloseRequested = AtomicBoolean()
    private val closeExecutions = mutableSetOf<CloseExecution>()

    override val activeOperationCount: Long
        get() = state.get() and ACTIVE_MASK

    override val isQuiescing: Boolean
        get() = state.get() and QUIESCING_MASK != 0L

    override val isClosed: Boolean
        get() = state.get() and CLOSED_MASK != 0L

    internal val failureSignal: Mono<Void> = failureSink.asMono()

    /**
     * Reports a terminal component failure to the owning runtime.
     */
    override fun reportFailure(error: Throwable) {
        val recorded = failures.record(error)
        if (recorded.installed) {
            failureSink.tryEmitError(error)
        }
    }

    /**
     * Registers an idempotent action that closes component intake at the global
     * quiet boundary. An action registered after admission closed runs immediately.
     */
    override fun onClose(action: () -> Unit) {
        val closeAction = CloseAction(action)
        closeActions += closeAction
        if (isClosed) {
            dispatchCloseAction(closeAction)
        }
    }

    /**
     * Attempts to admit one complete asynchronous operation.
     *
     * @return an idempotent activity lease, or `null` after runtime admission closes
     */
    override fun tryAcquire(): RuntimeActivity? {
        while (true) {
            val current = state.get()
            if (current and CLOSED_MASK != 0L) {
                return null
            }
            if (current and QUIESCING_MASK != 0L) {
                val acquired = synchronized(closeMonitor) {
                    val observed = state.get()
                    if (observed and CLOSED_MASK != 0L) {
                        false
                    } else {
                        val activeCount = observed and ACTIVE_MASK
                        check(activeCount < ACTIVE_MASK) {
                            "Runtime active operation count overflow."
                        }
                        if (state.compareAndSet(observed, observed + 1)) {
                            activityVersion.incrementAndGet()
                            true
                        } else {
                            null
                        }
                    }
                }
                when (acquired) {
                    true -> return ActivityLease(this)
                    false -> return null
                    null -> continue
                }
            }
            val activeCount = current and ACTIVE_MASK
            check(activeCount < ACTIVE_MASK) {
                "Runtime active operation count overflow."
            }
            if (state.compareAndSet(current, current + 1)) {
                activityVersion.incrementAndGet()
                return ActivityLease(this)
            }
        }
    }

    private fun release() {
        while (true) {
            val current = state.get()
            val activeCount = current and ACTIVE_MASK
            check(activeCount > 0) {
                "Runtime active operation count underflow."
            }
            val updated = current - 1
            if (state.compareAndSet(current, updated)) {
                if (updated == QUIESCING_MASK) {
                    scheduleCloseAfterQuietPeriod()
                }
                return
            }
        }
    }

    internal fun quiesce(): Mono<Void> {
        while (true) {
            val current = state.get()
            if (current and QUIESCING_MASK != 0L) {
                break
            }
            val updated = current or QUIESCING_MASK
            if (state.compareAndSet(current, updated)) {
                if (updated == QUIESCING_MASK) {
                    scheduleCloseAfterQuietPeriod()
                }
                break
            }
        }
        return quiescentSink.asMono()
    }

    /**
     * Closes admission without waiting for active work or the quiet period.
     *
     * The close actions are dispatched exactly once. They deliberately run outside
     * the caller so a broken intake close action cannot defeat the outer runtime's
     * force-stop deadline.
     */
    internal fun forceClose() {
        val executionsToCancel = synchronized(closeMonitor) {
            forceCloseRequested.set(true)
            while (true) {
                val current = state.get()
                val closed = current or QUIESCING_MASK or CLOSED_MASK
                if (current == closed || state.compareAndSet(current, closed)) {
                    break
                }
            }
            quietPeriodTask.getAndSet(null)?.dispose()
            closeExecutions.filterNot(CloseExecution::runWhenForced)
        }
        executionsToCancel.forEach { execution ->
            execution.cancel(Thread.currentThread())
        }
        dispatchCloseActions()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleCloseAfterQuietPeriod() {
        val expectedActivityVersion = synchronized(closeMonitor) {
            if (state.get() != QUIESCING_MASK) {
                return
            }
            activityVersion.get()
        }
        val scheduledTask = try {
            scheduler.schedule(
                { closeIfStillIdle(expectedActivityVersion) },
                shutdownQuietPeriodNanos,
                TimeUnit.NANOSECONDS,
            )
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            val schedulingAttemptStillCurrent = synchronized(closeMonitor) {
                state.get() == QUIESCING_MASK &&
                    activityVersion.get() == expectedActivityVersion
            }
            if (schedulingAttemptStillCurrent) {
                reportFailure(error)
                quiescentSink.tryEmitError(error)
            }
            return
        }
        val replacedTask = synchronized(closeMonitor) {
            if (
                state.get() == QUIESCING_MASK &&
                activityVersion.get() == expectedActivityVersion
            ) {
                quietPeriodTask.getAndSet(scheduledTask)
            } else {
                scheduledTask
            }
        }
        if (replacedTask === scheduledTask) {
            scheduledTask.dispose()
        } else {
            replacedTask?.dispose()
        }
    }

    private fun closeIfStillIdle(expectedActivityVersion: Long) {
        val admissionClosed = synchronized(closeMonitor) {
            if (activityVersion.get() != expectedActivityVersion) {
                false
            } else {
                state.compareAndSet(QUIESCING_MASK, QUIESCING_MASK or CLOSED_MASK)
            }
        }
        if (!admissionClosed) {
            return
        }
        quietPeriodTask.getAndSet(null)?.dispose()
        dispatchCloseActions()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchCloseActions() {
        if (!closeActionsStarted.compareAndSet(false, true)) {
            return
        }
        try {
            executeCloseAction(::runCloseActions)
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportCloseFailure(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchCloseAction(closeAction: CloseAction) {
        try {
            executeCloseAction {
                forceAllReporting(
                    forceActions = listOf(closeAction::run),
                    reportFailure = ::reportFailure,
                )?.let { closeFailure ->
                    quiescentSink.tryEmitError(closeFailure)
                }
            }
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportCloseFailure(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeCloseAction(action: () -> Unit) {
        val closeExecution = synchronized(closeMonitor) {
            CloseExecution(
                action = action,
                runWhenForced = forceCloseRequested.get(),
            ).also(closeExecutions::add)
        }
        val accepted = if (closeExecutor == null) {
            RuntimeCleanupExecutor.execute(closeExecution)
        } else {
            try {
                closeExecutor.execute(closeExecution)
                true
            } catch (error: Throwable) {
                synchronized(closeMonitor) {
                    closeExecutions -= closeExecution
                }
                throw error
            }
        }
        if (!accepted) {
            synchronized(closeMonitor) {
                closeExecutions -= closeExecution
            }
            throw RejectedExecutionException(
                "The process-wide Wow runtime cleanup executor is saturated.",
            )
        }
    }

    private fun runCloseActions() {
        val closeFailure = forceAllReporting(
            forceActions = closeActions.map { closeAction -> closeAction::run },
            reportFailure = ::reportFailure,
        )
        if (closeFailure == null) {
            quiescentSink.tryEmitEmpty()
        } else {
            quiescentSink.tryEmitError(closeFailure)
        }
    }

    private fun reportCloseFailure(error: Throwable) {
        reportFailure(error)
        quiescentSink.tryEmitError(error)
    }

    private class CloseAction(
        private val action: () -> Unit,
    ) {
        private val invoked = AtomicBoolean()

        fun run() {
            if (invoked.compareAndSet(false, true)) {
                action()
            }
        }
    }

    private inner class CloseExecution(
        private val action: () -> Unit,
        val runWhenForced: Boolean,
    ) : Runnable {
        private val cancelled = AtomicBoolean()
        private val runningThread = AtomicReference<Thread?>()

        override fun run() {
            if (cancelled.get()) {
                remove()
                return
            }
            val currentThread = Thread.currentThread()
            runningThread.set(currentThread)
            try {
                if (!cancelled.get()) {
                    action()
                }
            } finally {
                runningThread.set(null)
                Thread.interrupted()
                remove()
            }
        }

        fun cancel(caller: Thread) {
            cancelled.set(true)
            if (closeExecutor == null && RuntimeCleanupExecutor.remove(this)) {
                remove()
                return
            }
            runningThread.get()
                ?.takeUnless { running -> running === caller }
                ?.interrupt()
        }

        private fun remove() {
            synchronized(closeMonitor) {
                closeExecutions -= this
            }
        }
    }

    private class ActivityLease(
        private val runtimeContext: DefaultRuntimeContext,
    ) : RuntimeActivity {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runtimeContext.release()
            }
        }
    }
}
