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

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolates subscriber callbacks from batch lanes. Graceful shutdown reports
 * termination after every accepted callback drains; force shutdown interrupts
 * running callbacks and moves detached terminal signals to a coordinator-owned,
 * bounded executor so an uncooperative callback cannot hold logical termination
 * or starve another coordinator.
 */
internal class BatchResultDispatcher(
    private val name: String,
    maxPendingItems: Int,
    onTerminated: () -> Unit,
) {
    private enum class State {
        OPEN,
        GRACEFUL_SHUTDOWN,
        FORCE_SHUTDOWN,
        TERMINATED,
    }

    private val lifecycleMonitor = Any()
    private val pendingSignals = AtomicInteger()
    private val primaryTerminated = AtomicBoolean()
    private val forceShutdownInitiated = AtomicBoolean()
    private val onTerminated = AtomicReference<(() -> Unit)?>(onTerminated)
    private val dispatchToken = Any()
    private val threadCount = RESULT_DISPATCHER_THREADS.coerceAtMost(maxPendingItems)
    private val hardForceRequested = AtomicBoolean()
    private val activeDetachedThreads = ConcurrentHashMap.newKeySet<Thread>()
    private val executor = object : ThreadPoolExecutor(
        threadCount,
        threadCount,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingItems),
        { runnable ->
            Thread(
                runnable,
                "$name-batch-result-${RESULT_THREAD_SEQUENCE.incrementAndGet()}"
            ).apply {
                isDaemon = true
            }
        }
    ) {
        override fun terminated() {
            primaryTerminated.set(true)
            tryNotifyTerminated()
        }
    }
    private val detachedSignalExecutor = object : ThreadPoolExecutor(
        threadCount,
        threadCount,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingItems),
        { runnable ->
            Thread(
                runnable,
                "$name-batch-detached-result-" +
                    DETACHED_RESULT_THREAD_SEQUENCE.incrementAndGet(),
            ).apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ) {
        override fun beforeExecute(thread: Thread, task: Runnable) {
            activeDetachedThreads.add(thread)
            super.beforeExecute(thread, task)
        }

        override fun afterExecute(task: Runnable, failure: Throwable?) {
            try {
                super.afterExecute(task, failure)
            } finally {
                activeDetachedThreads.remove(Thread.currentThread())
            }
        }
    }

    @Volatile
    private var state = State.OPEN

    val isDispatchingResult: Boolean
        get() = DISPATCH_CONTEXT.get() === dispatchToken

    /**
     * Registers a result before any terminal transition can close this
     * dispatcher. Registration never invokes [signal] on the caller thread.
     */
    fun prepareDispatch(signal: () -> Unit): PreparedSignal {
        val signalTask = ResultSignalTask(signal)
        return synchronized(lifecycleMonitor) {
            when (state) {
                State.OPEN -> {
                    pendingSignals.incrementAndGet()
                    try {
                        executor.execute(signalTask)
                        PreparedSignal.accepted()
                    } catch (_: RejectedExecutionException) {
                        PreparedSignal.inline(signalTask::run)
                    }
                }

                State.GRACEFUL_SHUTDOWN -> {
                    pendingSignals.incrementAndGet()
                    PreparedSignal.inline(signalTask::run)
                }

                State.FORCE_SHUTDOWN,
                State.TERMINATED,
                -> PreparedSignal.rejected()
            }
        }
    }

    fun dispatch(signal: () -> Unit): Boolean {
        val preparedSignal = prepareDispatch(signal)
        if (!preparedSignal.accepted) {
            return false
        }
        preparedSignal.startFallbackIfNeeded()
        return true
    }

    fun shutdown(detachedSignals: Iterable<() -> Unit> = emptyList()) {
        val initiateShutdown = synchronized(lifecycleMonitor) {
            when (state) {
                State.OPEN -> {
                    state = State.GRACEFUL_SHUTDOWN
                    detachedSignals.forEach(::dispatchDetached)
                    true
                }

                State.GRACEFUL_SHUTDOWN -> {
                    detachedSignals.forEach(::dispatchDetached)
                    false
                }

                State.FORCE_SHUTDOWN,
                State.TERMINATED,
                -> false
            }
        }
        if (initiateShutdown) {
            executor.shutdown()
        }
    }

    fun forceShutdown(forcedSignals: Iterable<() -> Unit> = emptyList()) {
        val initiateForceShutdown = synchronized(lifecycleMonitor) {
            hardForceRequested.set(true)
            when (state) {
                State.OPEN,
                State.GRACEFUL_SHUTDOWN,
                -> {
                    state = State.FORCE_SHUTDOWN
                    pendingSignals.set(0)
                    true
                }

                State.FORCE_SHUTDOWN,
                State.TERMINATED,
                -> false
            }
        }
        activeDetachedThreads.forEach(Thread::interrupt)
        if (initiateForceShutdown) {
            val queuedSignals = executor.shutdownNow()
            queuedSignals.forEach { queuedSignal -> (queuedSignal as ResultSignalTask).abandon() }
            synchronized(lifecycleMonitor) {
                forcedSignals.forEach(::dispatchForced)
                forceShutdownInitiated.set(true)
            }
            tryNotifyTerminated()
        }
    }

    private fun dispatchForced(signal: () -> Unit) {
        dispatchDetachedSignal(
            signal = signal,
            interruptCallback = true,
        )
    }

    private fun dispatchDetached(signal: () -> Unit) {
        dispatchDetachedSignal(
            signal = signal,
            interruptCallback = false,
        )
    }

    private fun dispatchDetachedSignal(
        signal: () -> Unit,
        interruptCallback: Boolean,
    ) {
        detachedSignalExecutor.execute {
            if (interruptCallback || hardForceRequested.get()) {
                // Hard force-stop callbacks receive an interruption signal
                // without leaking it into the next pooled callback.
                Thread.currentThread().interrupt()
            }
            try {
                withDispatchContext(dispatchToken, signal)
            } finally {
                if (interruptCallback || hardForceRequested.get()) {
                    Thread.interrupted()
                }
            }
        }
    }

    private fun signalCompleted() {
        val shouldNotify = synchronized(lifecycleMonitor) {
            when (state) {
                State.OPEN,
                State.GRACEFUL_SHUTDOWN,
                -> {
                    check(pendingSignals.decrementAndGet() >= 0) {
                        "Batch result signal count underflow."
                    }
                    true
                }

                State.FORCE_SHUTDOWN,
                State.TERMINATED,
                -> false
            }
        }
        if (shouldNotify) {
            tryNotifyTerminated()
        }
    }

    private fun tryNotifyTerminated() {
        val terminationCallback = synchronized(lifecycleMonitor) {
            val canTerminate = when (state) {
                State.GRACEFUL_SHUTDOWN ->
                    primaryTerminated.get() && pendingSignals.get() == 0

                State.FORCE_SHUTDOWN -> forceShutdownInitiated.get()
                State.OPEN,
                State.TERMINATED,
                -> false
            }
            if (canTerminate) {
                state = State.TERMINATED
                onTerminated.getAndSet(null)
            } else {
                null
            }
        }
        if (terminationCallback == null) {
            return
        }
        detachedSignalExecutor.shutdown()
        terminationCallback()
    }

    private inner class ResultSignalTask(
        private val signal: () -> Unit,
    ) : Runnable {
        private val invoked = AtomicBoolean()
        private val pendingCompleted = AtomicBoolean()

        override fun run() {
            runSignal()
        }

        private fun runSignal() {
            if (!invoked.compareAndSet(false, true)) {
                return
            }
            try {
                withDispatchContext(dispatchToken, signal)
            } finally {
                completePending()
            }
        }

        fun abandon() {
            invoked.compareAndSet(false, true)
            completePending()
        }

        private fun completePending() {
            if (pendingCompleted.compareAndSet(false, true)) {
                signalCompleted()
            }
        }
    }

    internal class PreparedSignal private constructor(
        val accepted: Boolean,
        private val inlineFallback: (() -> Unit)? = null,
    ) {
        fun startFallbackIfNeeded() {
            inlineFallback?.invoke()
        }

        companion object {
            fun accepted(): PreparedSignal = PreparedSignal(accepted = true)

            fun inline(fallback: () -> Unit): PreparedSignal =
                PreparedSignal(
                    accepted = true,
                    inlineFallback = fallback,
                )

            fun rejected(): PreparedSignal = PreparedSignal(accepted = false)
        }
    }

    private companion object {
        const val RESULT_DISPATCHER_THREADS: Int = 4
        val RESULT_THREAD_SEQUENCE: AtomicInteger = AtomicInteger()
        val DETACHED_RESULT_THREAD_SEQUENCE: AtomicInteger = AtomicInteger()
        val DISPATCH_CONTEXT = ThreadLocal<Any>()

        fun withDispatchContext(
            dispatchToken: Any,
            signal: () -> Unit,
        ) {
            val previousContext = DISPATCH_CONTEXT.get()
            DISPATCH_CONTEXT.set(dispatchToken)
            try {
                signal()
            } finally {
                if (previousContext == null) {
                    DISPATCH_CONTEXT.remove()
                } else {
                    DISPATCH_CONTEXT.set(previousContext)
                }
            }
        }
    }
}
