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
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultRuntimeContext(
    private val shutdownQuietPeriod: Duration,
    private val scheduler: Scheduler,
) : RuntimeContext {
    private enum class AdmissionState {
        OPEN,
        QUIESCING,
        CLOSED,
    }

    private val monitor = Any()
    private val failureMonitor = Any()
    private val failureSink = Sinks.many().multicast().directBestEffort<Throwable>()
    private val admissionCloseActions = mutableListOf<() -> Unit>()
    private var admissionState = AdmissionState.OPEN
    private var activeOperations = 0L
    private var quietGeneration = 0L
    private var quietTask: Disposable? = null
    private var onQuiet: (() -> Unit)? = null

    val failureSignal: Flux<Throwable> = failureSink.asFlux()

    override val activeOperationCount: Long
        get() = synchronized(monitor) {
            activeOperations
        }

    override val isQuiescing: Boolean
        get() = synchronized(monitor) {
            admissionState != AdmissionState.OPEN
        }

    override val isAdmissionClosed: Boolean
        get() = synchronized(monitor) {
            admissionState == AdmissionState.CLOSED
        }

    override fun tryAcquire(): RuntimeActivity? {
        synchronized(monitor) {
            if (admissionState == AdmissionState.CLOSED) {
                return null
            }
            activeOperations++
            quietGeneration++
            quietTask?.dispose()
            quietTask = null
        }
        return ActivityLease(::release)
    }

    override fun onAdmissionClose(action: () -> Unit) {
        val runImmediately = synchronized(monitor) {
            if (admissionState == AdmissionState.CLOSED) {
                true
            } else {
                admissionCloseActions += action
                false
            }
        }
        if (runImmediately) {
            runAdmissionCloseAction(action)
        }
    }

    override fun reportFailure(error: Throwable) {
        synchronized(failureMonitor) {
            failureSink.tryEmitNext(error)
        }
    }

    fun beginQuiescence(onQuiet: () -> Unit) {
        val schedule = synchronized(monitor) {
            if (admissionState != AdmissionState.OPEN) {
                false
            } else {
                admissionState = AdmissionState.QUIESCING
                this.onQuiet = onQuiet
                activeOperations == 0L
            }
        }
        if (schedule) {
            scheduleQuietBoundary()
        }
    }

    fun forceClose() {
        val actions = synchronized(monitor) {
            if (admissionState == AdmissionState.CLOSED) {
                return
            }
            admissionState = AdmissionState.CLOSED
            quietGeneration++
            quietTask?.dispose()
            quietTask = null
            admissionCloseActions.toList().also {
                admissionCloseActions.clear()
            }
        }
        actions.forEach(::runAdmissionCloseAction)
    }

    private fun release() {
        val schedule = synchronized(monitor) {
            check(activeOperations > 0) {
                "Runtime activity counter underflow."
            }
            activeOperations--
            admissionState == AdmissionState.QUIESCING && activeOperations == 0L
        }
        if (schedule) {
            scheduleQuietBoundary()
        }
    }

    private fun scheduleQuietBoundary() {
        val generation = synchronized(monitor) {
            if (admissionState != AdmissionState.QUIESCING || activeOperations != 0L) {
                return
            }
            ++quietGeneration
        }
        val task = scheduler.schedule(
            { closeAdmissionAtQuietBoundary(generation) },
            shutdownQuietPeriod.toNanos(),
            TimeUnit.NANOSECONDS,
        )
        synchronized(monitor) {
            if (
                admissionState == AdmissionState.QUIESCING &&
                activeOperations == 0L &&
                quietGeneration == generation
            ) {
                quietTask?.dispose()
                quietTask = task
            } else {
                task.dispose()
            }
        }
    }

    private fun closeAdmissionAtQuietBoundary(generation: Long) {
        val close = synchronized(monitor) {
            if (
                admissionState != AdmissionState.QUIESCING ||
                activeOperations != 0L ||
                quietGeneration != generation
            ) {
                return
            }
            admissionState = AdmissionState.CLOSED
            quietTask = null
            val actions = admissionCloseActions.toList()
            admissionCloseActions.clear()
            AdmissionClose(actions, onQuiet)
        }
        close.actions.forEach(::runAdmissionCloseAction)
        close.onQuiet?.invoke()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runAdmissionCloseAction(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportFailure(error)
        }
    }

    private data class AdmissionClose(
        val actions: List<() -> Unit>,
        val onQuiet: (() -> Unit)?,
    )

    private class ActivityLease(
        private val release: () -> Unit,
    ) : RuntimeActivity {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release()
            }
        }
    }
}
