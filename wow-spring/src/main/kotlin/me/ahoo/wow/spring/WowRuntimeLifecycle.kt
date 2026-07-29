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
import reactor.core.Exceptions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The only Spring lifecycle owner for a complete [WowRuntime].
 */
const val WOW_RUNTIME_PHASE = DEFAULT_PHASE - 3072

class WowRuntimeLifecycle(
    private val wowRuntime: WowRuntime,
    private val unexpectedTerminationExecutor: Executor = CompletableFuture.delayedExecutor(
        0,
        TimeUnit.MILLISECONDS,
    ),
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
    }

    private val lifecycleMonitor = Any()
    private var terminationSubscription: Disposable? = null

    @Volatile
    private var state = State.NEW

    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        synchronized(lifecycleMonitor) {
            when (state) {
                State.RUNNING -> return
                State.NEW -> state = State.STARTING
                State.STARTING -> error("Lifecycle startup is already in progress.")
                State.STOPPING,
                State.TERMINATED,
                -> error("WowRuntime is one-shot and cannot restart.")
            }
            observeTermination()
        }
        try {
            wowRuntime.start().block()
            synchronized(lifecycleMonitor) {
                check(state == State.STARTING) {
                    "WowRuntime terminated while Spring was starting it."
                }
                state = State.RUNNING
            }
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            wowRuntime.forceStop()
            synchronized(lifecycleMonitor) {
                state = State.TERMINATED
            }
            throw error
        }
    }

    override fun stop() {
        if (!beginStopping()) {
            return
        }
        try {
            wowRuntime.stopGracefully().block()
        } finally {
            markTerminated()
        }
    }

    override fun stop(callback: Runnable) {
        if (!beginStopping()) {
            callback.run()
            return
        }
        wowRuntime.stopGracefully()
            .doFinally {
                markTerminated()
                callback.run()
            }
            .subscribe(
                {},
                { error ->
                    log.error(error) {
                        "Wow runtime shutdown failed."
                    }
                },
            )
    }

    override fun isRunning(): Boolean = state == State.RUNNING

    override fun isPauseable(): Boolean = false

    override fun getPhase(): Int = WOW_RUNTIME_PHASE

    private fun beginStopping(): Boolean =
        synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW -> {
                    observeTermination()
                    state = State.STOPPING
                    true
                }

                State.STARTING,
                State.RUNNING,
                -> {
                    state = State.STOPPING
                    true
                }

                State.STOPPING,
                State.TERMINATED,
                -> false
            }
        }

    private fun observeTermination() {
        if (terminationSubscription != null) {
            return
        }
        terminationSubscription = wowRuntime.terminationSignal.subscribe(
            {},
            ::runtimeTerminated,
            { runtimeTerminated(null) },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runtimeTerminated(error: Throwable?) {
        val unexpected = synchronized(lifecycleMonitor) {
            val wasRunning = state == State.RUNNING
            state = State.TERMINATED
            wasRunning
        }
        if (!unexpected) {
            return
        }
        val failure = error ?: IllegalStateException("Wow runtime terminated unexpectedly.")
        unexpectedTerminationExecutor.execute {
            try {
                onUnexpectedTermination(failure)
            } catch (callbackFailure: Throwable) {
                Exceptions.throwIfFatal(callbackFailure)
                log.error(callbackFailure) {
                    "Unexpected runtime termination callback failed."
                }
            }
        }
    }

    private fun markTerminated() {
        synchronized(lifecycleMonitor) {
            state = State.TERMINATED
        }
    }
}
