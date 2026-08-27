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

package me.ahoo.wow.spring

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.runtime.WowRuntime
import org.springframework.context.SmartLifecycle
import org.springframework.context.SmartLifecycle.DEFAULT_PHASE
import reactor.core.Disposable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spring lifecycle boundary for the single high-level Wow runtime.
 *
 * The phase is lower than Spring Boot's web-server start/stop phase so Wow is
 * ready before ingress opens and is stopped only after ingress has drained.
 * Unexpected runtime termination is reported through [onUnexpectedTermination];
 * the Starter uses that callback to close the application context.
 *
 * Construction is inert. The exclusive termination-control subscription is
 * installed on the first lifecycle start or stop operation.
 */
const val WOW_RUNTIME_PHASE = DEFAULT_PHASE - 3072

class WowRuntimeLifecycle(
    private val wowRuntime: WowRuntime,
    private val unexpectedTerminationExecutor: Executor = defaultLifecycleCallbackExecutor,
    private val onUnexpectedTermination: (Throwable) -> Unit = {},
) : SmartLifecycle {
    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        TERMINATED,
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val terminationThreadId = AtomicInteger()
        private val defaultLifecycleCallbackExecutor = Executor { command ->
            Thread(
                command,
                "wow-runtime-termination-${terminationThreadId.incrementAndGet()}",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private val lifecycleMonitor = Any()
    private val unexpectedTerminationDispatched = AtomicBoolean()
    private val unexpectedTerminationCallbackStarted = AtomicBoolean()
    private val terminationFuture = CompletableFuture<Void>()
    private var terminationControl: Disposable? = null

    @Volatile
    private var state = State.NEW

    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        ensureTerminationControl()
        val shouldStart = synchronized(lifecycleMonitor) {
            when (state) {
                State.RUNNING -> {
                    if (wowRuntime.isRunning) {
                        false
                    } else {
                        state = State.TERMINATED
                        restartNotSupported()
                    }
                }

                State.NEW -> {
                    state = State.STARTING
                    true
                }

                State.STARTING -> error("Lifecycle monitor must serialize startup.")
                State.STOPPING,
                State.TERMINATED,
                -> restartNotSupported()
            }
        }
        if (!shouldStart) {
            return
        }
        try {
            val startupSignal = checkNotNull(wowRuntime.start().materialize().block())
            startupSignal.throwable?.let { throw it }
            synchronized(lifecycleMonitor) {
                check(state == State.STARTING) {
                    "Lifecycle state changed while the Wow runtime was starting: $state."
                }
                state = State.RUNNING
            }
        } catch (error: Throwable) {
            synchronized(lifecycleMonitor) {
                state = State.TERMINATED
            }
            throw error
        }
    }

    private fun completeTermination(error: Throwable? = null) {
        val unexpected = markRuntimeTerminated()
        if (error == null) {
            terminationFuture.complete(null)
        } else {
            terminationFuture.completeExceptionally(error)
        }
        if (unexpected) {
            dispatchUnexpectedTermination(error)
        }
    }

    private fun restartNotSupported(): Nothing =
        error(
            "Wow runtime is one-shot and cannot restart after shutdown has begun. " +
                "Create a new ApplicationContext instead.",
        )

    private fun markRuntimeTerminated(): Boolean {
        val wasRunning = synchronized(lifecycleMonitor) {
            val running = state == State.RUNNING
            state = State.TERMINATED
            running
        }
        return wasRunning && unexpectedTerminationDispatched.compareAndSet(false, true)
    }

    private fun dispatchUnexpectedTermination(error: Throwable?) {
        val terminationFailure = error ?: IllegalStateException("Wow runtime terminated unexpectedly.")
        val notification = Runnable {
            if (unexpectedTerminationCallbackStarted.compareAndSet(false, true)) {
                notifyUnexpectedTermination(terminationFailure)
            }
        }
        val isolatedDispatch = Runnable {
            log.error(terminationFailure) {
                "Wow runtime terminated while the Spring application was still running."
            }
            dispatchUnexpectedTerminationCallback(notification)
        }
        dispatchLifecycleCallback(
            action = isolatedDispatch,
            failureMessage = "Could not isolate the unexpected runtime termination callback; " +
                "running it on the termination thread.",
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchLifecycleCallback(
        action: Runnable,
        failureMessage: String,
    ) {
        try {
            defaultLifecycleCallbackExecutor.execute(action)
        } catch (dispatchFailure: RuntimeException) {
            log.error(dispatchFailure) {
                failureMessage
            }
            action.run()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchUnexpectedTerminationCallback(notification: Runnable) {
        if (unexpectedTerminationExecutor === defaultLifecycleCallbackExecutor) {
            notification.run()
            return
        }
        try {
            unexpectedTerminationExecutor.execute(notification)
        } catch (dispatchFailure: RuntimeException) {
            log.error(dispatchFailure) {
                "Could not dispatch the unexpected runtime termination callback; " +
                    "running it on the isolated fallback thread."
            }
            notification.run()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun notifyUnexpectedTermination(terminationFailure: Throwable) {
        try {
            onUnexpectedTermination(terminationFailure)
        } catch (callbackFailure: Throwable) {
            log.error(callbackFailure) {
                "Unexpected runtime termination callback failed."
            }
        }
    }

    override fun stop() {
        ensureTerminationControl()
        if (!beginStopping()) {
            return
        }
        try {
            wowRuntime.stopGracefully()
            terminationFuture.join()
        } catch (error: CompletionException) {
            throw error.cause ?: error
        } finally {
            completeStop()
        }
    }

    override fun stop(callback: Runnable) {
        ensureTerminationControl()
        if (!beginStopping()) {
            callback.run()
            return
        }
        terminationFuture.whenComplete { _, completionFailure ->
            dispatchLifecycleCallback(
                action = Runnable {
                    if (completionFailure != null) {
                        val error = (completionFailure as? CompletionException)?.cause ?: completionFailure
                        log.error(error) {
                            "Wow runtime shutdown failed."
                        }
                    }
                    completeStop()
                    callback.run()
                },
                failureMessage = "Could not dispatch the Spring runtime stop callback; " +
                    "running it on the completion thread.",
            )
        }
        wowRuntime.stopGracefully()
    }

    private fun ensureTerminationControl() {
        synchronized(lifecycleMonitor) {
            if (terminationControl != null) {
                return
            }
            terminationControl = wowRuntime.claimTerminationControl(::completeTermination)
        }
    }

    private fun beginStopping(): Boolean =
        synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW,
                State.STARTING,
                State.RUNNING,
                -> {
                    state = State.STOPPING
                    true
                }

                State.STOPPING -> true
                State.TERMINATED -> false
            }
        }

    private fun completeStop() {
        synchronized(lifecycleMonitor) {
            state = State.TERMINATED
        }
    }

    override fun isRunning(): Boolean =
        (state == State.RUNNING || state == State.STOPPING) && wowRuntime.isRunning

    override fun isPauseable(): Boolean = false

    override fun getPhase(): Int = WOW_RUNTIME_PHASE
}
