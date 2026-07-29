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

import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.runtime.internal.FailureAccumulator
import me.ahoo.wow.runtime.internal.RuntimeComponentGroup
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Single high-level owner of the complete Wow processing runtime.
 */
class WowRuntime internal constructor(
    components: List<RuntimeComponent>,
    val shutdownTimeout: Duration,
    val shutdownQuietPeriod: Duration,
    private val quiescenceScheduler: Scheduler,
    private val deadlineScheduler: Scheduler,
) {
    internal constructor(
        components: List<RuntimeComponent>,
        shutdownTimeout: Duration,
        shutdownQuietPeriod: Duration,
        scheduler: Scheduler,
    ) : this(
        components = components,
        shutdownTimeout = shutdownTimeout,
        shutdownQuietPeriod = shutdownQuietPeriod,
        quiescenceScheduler = scheduler,
        deadlineScheduler = scheduler,
    )

    constructor(
        components: List<RuntimeComponent>,
        shutdownTimeout: Duration,
        shutdownQuietPeriod: Duration,
    ) : this(
        components = components,
        shutdownTimeout = shutdownTimeout,
        shutdownQuietPeriod = shutdownQuietPeriod,
        quiescenceScheduler = QUIESCENCE_SCHEDULER,
        deadlineScheduler = DEADLINE_SCHEDULER,
    )

    private companion object {
        val QUIESCENCE_SCHEDULER: Scheduler =
            Schedulers.newSingle("wow-runtime-quiescence", true)
        val DEADLINE_SCHEDULER: Scheduler =
            Schedulers.newSingle("wow-runtime-deadline", true)
    }

    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        QUIESCING,
        STOPPING_PENDING,
        STOPPING,
        FORCE_STOPPING,
        TERMINATED,
    }

    init {
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) {
            "shutdownTimeout must be positive."
        }
        require(!shutdownQuietPeriod.isNegative && shutdownQuietPeriod < shutdownTimeout) {
            "shutdownQuietPeriod must not be negative and must be shorter than shutdownTimeout."
        }
    }

    val components: List<RuntimeComponent> =
        Collections.unmodifiableList(components.toList())

    private val lifecycleMonitor = Any()
    private val failures = FailureAccumulator()
    private val runtimeContext = DefaultRuntimeContext(shutdownQuietPeriod, quiescenceScheduler)
    private val componentGroup = RuntimeComponentGroup(this.components)
    private val terminationSink = Sinks.empty<Void>()
    private val rawTerminationSignal = terminationSink.asMono()
    private val gracefulStopDisposables = Disposables.composite()
    private var failureSubscription: Disposable? = null
    private var deadlineTask: Disposable? = null
    private var startupInProgress = false
    private var deadlineInitiated = false
    private var quiescenceInitiated = false
    private var forcePassInProgress = false
    private var forceReplayAfterStartup = false

    @Volatile
    private var state = State.NEW

    val isRunning: Boolean
        get() = state == State.RUNNING

    /**
     * Hot, replayable completion of the runtime.
     */
    val terminationSignal: Mono<Void> = rawTerminationSignal

    /**
     * Executes the all-component preparation barrier and then opens processing.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(): Mono<Void> =
        Mono.defer {
            synchronized(lifecycleMonitor) {
                check(state == State.NEW) {
                    "WowRuntime is one-shot and cannot start from state [$state]."
                }
                state = State.STARTING
                startupInProgress = true
                failureSubscription = runtimeContext.failureSignal.subscribe(::handleRuntimeFailure)
            }
            try {
                componentGroup.prepare(runtimeContext, ::ensureStartupContinues)
                componentGroup.start(::ensureStartupContinues)
                synchronized(lifecycleMonitor) {
                    ensureStartupContinues()
                    startupInProgress = false
                    state = State.RUNNING
                }
                Mono.empty()
            } catch (_: StartupInterruptedException) {
                abortStartup()
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                abortStartup(error)
            }
        }

    private fun abortStartup(error: Throwable? = null): Mono<Void> {
        error?.let(failures::record)
        val forcedTermination = synchronized(lifecycleMonitor) {
            startupInProgress = false
            if (state == State.FORCE_STOPPING) {
                ForcedTermination(true, nextForceAction())
            } else {
                ForcedTermination(false, ForceAction.NONE)
            }
        }
        if (forcedTermination.forced) {
            executeForceAction(forcedTermination.action)
        } else {
            requestStop()
        }
        return rawTerminationSignal
    }

    /**
     * Starts one shared graceful shutdown that callers cannot cancel.
     */
    fun stopGracefully(): Mono<Void> =
        Mono.defer {
            requestStop()
            rawTerminationSignal
        }

    /**
     * Immediately closes admission and force-stops every component.
     */
    fun forceStop() {
        forceStop(null)
    }

    private fun requestStop() {
        val actions = synchronized(lifecycleMonitor) {
            when (state) {
                State.NEW,
                State.RUNNING,
                -> {
                    state = State.QUIESCING
                }

                State.STARTING -> state = State.QUIESCING
                State.QUIESCING,
                State.STOPPING_PENDING,
                State.STOPPING,
                State.FORCE_STOPPING,
                State.TERMINATED,
                -> Unit
            }
            val startDeadline = state == State.QUIESCING && !deadlineInitiated
            if (startDeadline) {
                deadlineInitiated = true
            }
            val startQuiescence =
                state == State.QUIESCING &&
                    !startupInProgress &&
                    !quiescenceInitiated
            if (startQuiescence) {
                quiescenceInitiated = true
            }
            ShutdownActions(startDeadline, startQuiescence)
        }
        if (actions.startDeadline && !scheduleDeadline()) {
            return
        }
        if (actions.startQuiescence) {
            val runtimeIsQuiescing = synchronized(lifecycleMonitor) {
                state == State.QUIESCING
            }
            if (runtimeIsQuiescing) {
                runtimeContext.beginQuiescence(::beginComponentStop)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun beginComponentStop() {
        val queue = synchronized(lifecycleMonitor) {
            if (state != State.QUIESCING) {
                false
            } else {
                state = State.STOPPING_PENDING
                true
            }
        }
        if (!queue) {
            return
        }
        try {
            val cleanupTask = quiescenceScheduler.schedule(::startComponentStop)
            gracefulStopDisposables.add(cleanupTask)
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failures.record(error)
            forceStop(null)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startComponentStop() {
        val start = synchronized(lifecycleMonitor) {
            if (state != State.STOPPING_PENDING) {
                false
            } else {
                state = State.STOPPING
                true
            }
        }
        if (!start) {
            return
        }
        try {
            val stopSubscription = componentGroup.stopGracefully().subscribe(
                { },
                { error ->
                    failures.record(error)
                    forceStop(null)
                },
                ::completeGracefulTermination,
            )
            gracefulStopDisposables.add(stopSubscription)
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failures.record(error)
            forceStop(null)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDeadline(): Boolean {
        return try {
            val task = deadlineScheduler.schedule(
                {
                    forceStop(
                        TimeoutException(
                            "WowRuntime shutdown timed out after $shutdownTimeout."
                        )
                    )
                },
                shutdownTimeout.toNanos(),
                TimeUnit.NANOSECONDS,
            )
            val keepTask = synchronized(lifecycleMonitor) {
                if (state == State.TERMINATED) {
                    false
                } else {
                    deadlineTask = task
                    true
                }
            }
            if (!keepTask) {
                task.dispose()
            }
            true
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            forceStop(error)
            false
        }
    }

    private fun forceStop(trigger: Throwable?) {
        val force = synchronized(lifecycleMonitor) {
            if (state == State.TERMINATED || state == State.FORCE_STOPPING) {
                false
            } else {
                trigger?.let(failures::record)
                forceReplayAfterStartup = startupInProgress
                forcePassInProgress = true
                state = State.FORCE_STOPPING
                true
            }
        }
        if (!force) {
            return
        }
        runtimeContext.forceClose()
        gracefulStopDisposables.dispose()
        runForcePass()
    }

    private fun runForcePass() {
        componentGroup.forceStop()?.let(failures::record)
        val nextAction = synchronized(lifecycleMonitor) {
            forcePassInProgress = false
            nextForceAction()
        }
        executeForceAction(nextAction)
    }

    private fun nextForceAction(): ForceAction {
        if (
            state != State.FORCE_STOPPING ||
            forcePassInProgress ||
            startupInProgress
        ) {
            return ForceAction.NONE
        }
        if (forceReplayAfterStartup) {
            forceReplayAfterStartup = false
            forcePassInProgress = true
            return ForceAction.RUN_FORCE_PASS
        }
        return ForceAction.COMPLETE
    }

    private fun executeForceAction(action: ForceAction) {
        when (action) {
            ForceAction.NONE -> Unit
            ForceAction.RUN_FORCE_PASS -> runForcePass()
            ForceAction.COMPLETE -> completeTermination(State.FORCE_STOPPING)
        }
    }

    private fun handleRuntimeFailure(error: Throwable) {
        failures.record(error)
        requestStop()
    }

    private fun ensureStartupContinues() {
        if (state != State.STARTING) {
            throw StartupInterruptedException(state)
        }
    }

    private class StartupInterruptedException(state: State) :
        IllegalStateException("WowRuntime startup was interrupted by state [$state].")

    private data class ShutdownActions(
        val startDeadline: Boolean,
        val startQuiescence: Boolean,
    )

    private data class ForcedTermination(
        val forced: Boolean,
        val action: ForceAction,
    )

    private enum class ForceAction {
        NONE,
        RUN_FORCE_PASS,
        COMPLETE,
    }

    private fun completeGracefulTermination() {
        completeTermination(State.STOPPING)
    }

    private fun completeTermination(expectedState: State) {
        val complete = synchronized(lifecycleMonitor) {
            if (state != expectedState) {
                false
            } else {
                state = State.TERMINATED
                true
            }
        }
        if (!complete) {
            return
        }
        deadlineTask?.dispose()
        gracefulStopDisposables.dispose()
        failureSubscription?.dispose()
        val failure = failures.seal()
        if (failure == null) {
            terminationSink.tryEmitEmpty()
        } else {
            terminationSink.tryEmitError(failure)
        }
    }
}
