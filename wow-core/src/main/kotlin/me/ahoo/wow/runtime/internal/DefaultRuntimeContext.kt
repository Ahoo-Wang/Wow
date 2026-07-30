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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks complete-runtime activity and detects a stable idle shutdown boundary.
 *
 * Quiescing continues to admit work while activity is present or while the
 * configured quiet period is still open. Each new operation resets that period.
 * Once the runtime remains idle for the complete period, admission closes before
 * quiescence completes. A fatal failure closes admission immediately, skips the
 * quiet period, and still waits for admitted work to drain. The owning runtime
 * quiesces every component after either admission boundary closes.
 */
internal class DefaultRuntimeContext(
    private val shutdownQuietPeriod: Duration = Duration.ZERO,
    private val scheduler: Scheduler = Schedulers.parallel(),
    private val failureHandler: (Throwable) -> Unit = {},
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
    private val admissionClosedSink = Sinks.empty<Void>()
    private val quiescentSink = Sinks.empty<Void>()
    private val quiescenceMonitor = Any()

    internal val activeOperationCount: Long
        get() = state.get() and ACTIVE_MASK

    internal val isAdmissionClosed: Boolean
        get() = state.get() and CLOSED_MASK != 0L

    /**
     * Reports a terminal component failure to the owning runtime.
     */
    override fun reportFailure(error: Throwable) = failureHandler(error)

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
                val acquired = synchronized(quiescenceMonitor) {
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
                when {
                    updated == QUIESCING_MASK -> scheduleCloseAfterQuietPeriod()
                    updated and CLOSED_MASK != 0L &&
                        updated and ACTIVE_MASK == 0L -> quiescentSink.tryEmitEmpty()
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

    internal fun admissionClosed(): Mono<Void> = admissionClosedSink.asMono()

    /**
     * Immediately closes admission but lets already admitted work drain.
     */
    internal fun closeAdmissionAndDrain() {
        val drained = synchronized(quiescenceMonitor) {
            while (true) {
                val current = state.get()
                val closed = current or QUIESCING_MASK or CLOSED_MASK
                if (current == closed || state.compareAndSet(current, closed)) {
                    break
                }
            }
            quietPeriodTask.getAndSet(null)?.dispose()
            state.get() and ACTIVE_MASK == 0L
        }
        admissionClosedSink.tryEmitEmpty()
        if (drained) {
            quiescentSink.tryEmitEmpty()
        }
    }

    /**
     * Closes admission without waiting for active work or the quiet period.
     */
    internal fun forceClose() {
        synchronized(quiescenceMonitor) {
            while (true) {
                val current = state.get()
                val closed = current or QUIESCING_MASK or CLOSED_MASK
                if (current == closed || state.compareAndSet(current, closed)) {
                    break
                }
            }
            quietPeriodTask.getAndSet(null)?.dispose()
        }
        admissionClosedSink.tryEmitEmpty()
        quiescentSink.tryEmitEmpty()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleCloseAfterQuietPeriod() {
        val expectedActivityVersion = synchronized(quiescenceMonitor) {
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
            val schedulingAttemptStillCurrent = synchronized(quiescenceMonitor) {
                state.get() == QUIESCING_MASK &&
                    activityVersion.get() == expectedActivityVersion
            }
            if (schedulingAttemptStillCurrent) {
                reportFailure(error)
                admissionClosedSink.tryEmitError(error)
                quiescentSink.tryEmitError(error)
            }
            return
        }
        val replacedTask = synchronized(quiescenceMonitor) {
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
        val admissionClosed = synchronized(quiescenceMonitor) {
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
        admissionClosedSink.tryEmitEmpty()
        quiescentSink.tryEmitEmpty()
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
