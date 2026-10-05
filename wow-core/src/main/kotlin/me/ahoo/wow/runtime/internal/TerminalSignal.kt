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

import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Operators
import reactor.util.context.Context
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reserves terminal-delivery capacity when a subscriber is installed.
 *
 * The underlying task queue is used exclusively by this dispatcher. Therefore
 * an acquired permit guarantees one terminal task can be accepted later,
 * without executing user code on the source completion thread.
 */
internal interface TerminalSignalDispatcher : Disposable {
    fun tryAcquire(): TerminalSignalPermit?
}

internal interface TerminalSignalPermit : Disposable {
    fun dispatch(action: Runnable): Boolean

    fun onDispatcherDisposed(action: Runnable)
}

/**
 * Retains the source's hot/replayable terminal state while isolating every
 * admitted subscriber callback from lifecycle and deadline threads.
 *
 * Capacity is reserved at subscription time. A subscriber beyond the bounded
 * capacity receives [RejectedExecutionException] immediately on its own
 * subscription thread; an admitted subscriber either receives the original
 * terminal signal or explicitly cancels.
 */
internal fun Mono<Void>.publishTerminalSignal(
    dispatcher: TerminalSignalDispatcher = DefaultRuntimeExecutionResources.terminationDispatcher,
): Mono<Void> =
    TerminalSignalMono(this, dispatcher)

/**
 * Installs one trusted control-plane callback on an already isolated
 * dispatcher. Admission failure is reported to the claimant synchronously.
 */
@Suppress("TooGenericExceptionCaught")
internal fun Mono<Void>.subscribeTerminalSignal(
    dispatcher: TerminalSignalDispatcher,
    onTermination: (Throwable?) -> Unit,
    rejectionMessage: String = "The process-wide Wow terminal signal dispatcher is saturated.",
): Disposable {
    val permit = dispatcher.tryAcquire()
        ?: throw RejectedExecutionException(rejectionMessage)
    val subscriber = TerminalSignalSubscriber(
        actual = TerminalSignalCallbackSubscriber(onTermination),
        permit = permit,
    )
    try {
        subscribe(subscriber)
    } catch (error: Throwable) {
        subscriber.dispose()
        throw error
    }
    return subscriber
}

private class TerminalSignalMono(
    private val source: Mono<Void>,
    private val dispatcher: TerminalSignalDispatcher,
) : Mono<Void>() {
    @Suppress("TooGenericExceptionCaught")
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val permit = dispatcher.tryAcquire()
        if (permit == null) {
            Operators.error(
                actual,
                RejectedExecutionException(
                    "The process-wide Wow terminal signal dispatcher is saturated.",
                ),
            )
            return
        }
        val subscriber = TerminalSignalSubscriber(actual, permit)
        try {
            source.subscribe(subscriber)
        } catch (error: Throwable) {
            subscriber.dispose()
            throw error
        }
    }
}

private class TerminalSignalSubscriber(
    actual: CoreSubscriber<in Void>,
    private val permit: TerminalSignalPermit,
) : CoreSubscriber<Void>,
    Subscription,
    Disposable {
    private val actual = AtomicReference<CoreSubscriber<in Void>?>(actual)
    private val upstream = AtomicReference<Subscription?>()
    private val terminated = AtomicBoolean()

    init {
        permit.onDispatcherDisposed(
            Runnable {
                this.actual.set(null)
                upstream.getAndSet(null)?.cancel()
            },
        )
    }

    override fun currentContext(): Context =
        actual.get()?.currentContext() ?: Context.empty()

    override fun onSubscribe(subscription: Subscription) {
        if (!upstream.compareAndSet(null, subscription)) {
            subscription.cancel()
            return
        }
        val downstream = actual.get()
        if (downstream == null) {
            cancelUpstreamAndReleasePermit()
            return
        }
        downstream.onSubscribe(this)
    }

    override fun request(n: Long) {
        if (Operators.validate(n)) {
            upstream.get()?.request(n)
        }
    }

    override fun cancel() {
        actual.set(null)
        cancelUpstreamAndReleasePermit()
    }

    override fun dispose() = cancel()

    override fun isDisposed(): Boolean = actual.get() == null

    override fun onNext(value: Void) = Unit

    override fun onError(error: Throwable) {
        dispatchTerminal { downstream ->
            downstream.onError(error)
        }
    }

    override fun onComplete() {
        dispatchTerminal(CoreSubscriber<in Void>::onComplete)
    }

    private fun dispatchTerminal(action: (CoreSubscriber<in Void>) -> Unit) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        val accepted = permit.dispatch(
            Runnable {
                val downstream = actual.getAndSet(null) ?: return@Runnable
                upstream.set(null)
                action(downstream)
            },
        )
        if (accepted) {
            return
        }
        actual.set(null)
        cancelUpstreamAndReleasePermit()
    }

    private fun cancelUpstreamAndReleasePermit() {
        try {
            upstream.getAndSet(null)?.cancel()
        } finally {
            permit.dispose()
        }
    }
}

private class TerminalSignalCallbackSubscriber(
    private val onTermination: (Throwable?) -> Unit,
) : CoreSubscriber<Void> {
    override fun currentContext(): Context = Context.empty()

    override fun onSubscribe(subscription: Subscription) {
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(value: Void) = Unit

    override fun onError(error: Throwable) {
        onTermination(error)
    }

    override fun onComplete() {
        onTermination(null)
    }
}

/**
 * Creates a bounded daemon dispatcher with its own threads, whose logical
 * queue is limited by admission permits rather than by terminal-time rejection.
 */
internal fun newTerminalSignalDispatcher(
    threadNamePrefix: String,
    threadCap: Int = TERMINAL_SIGNAL_THREAD_CAP,
    queuedTaskCapacity: Int = TERMINAL_SIGNAL_QUEUE_CAPACITY,
): TerminalSignalDispatcher {
    require(threadCap > 0) {
        "threadCap must be positive."
    }
    require(queuedTaskCapacity >= 0) {
        "queuedTaskCapacity must not be negative."
    }
    return newTerminalSignalDispatcher(
        RuntimeLifecyclePool(threadNamePrefix, threadCap)
            .lane(threadNamePrefix, threadCap, queuedTaskCapacity),
        threadCap,
        queuedTaskCapacity,
    )
}

/**
 * Creates a bounded dispatcher on [lane], which must run [threadCap] tasks at
 * once and queue [queuedTaskCapacity] more: admission permits never exceed
 * what the lane accepts. Disposing the dispatcher disposes the lane.
 */
internal fun newTerminalSignalDispatcher(
    lane: RuntimeLifecycleLane,
    threadCap: Int,
    queuedTaskCapacity: Int,
): TerminalSignalDispatcher =
    BoundedTerminalSignalDispatcher(
        lane = lane,
        capacity = Math.addExact(threadCap, queuedTaskCapacity),
    )

internal object ImmediateTerminalSignalDispatcher : TerminalSignalDispatcher {
    override fun tryAcquire(): TerminalSignalPermit =
        object : TerminalSignalPermit {
            private val disposed = AtomicBoolean()

            override fun dispatch(action: Runnable): Boolean {
                if (disposed.get()) {
                    return false
                }
                try {
                    action.run()
                } finally {
                    disposed.set(true)
                }
                return true
            }

            override fun onDispatcherDisposed(action: Runnable) = Unit

            override fun dispose() {
                disposed.set(true)
            }

            override fun isDisposed(): Boolean = disposed.get()
        }

    override fun dispose() = Unit

    override fun isDisposed(): Boolean = false
}

private class BoundedTerminalSignalDispatcher(
    private val lane: RuntimeLifecycleLane,
    capacity: Int,
) : TerminalSignalDispatcher {
    private val lifecycleMonitor = Any()
    private val disposed = AtomicBoolean()
    private val permits = Semaphore(capacity, true)
    private val activePermits = mutableSetOf<Permit>()

    override fun tryAcquire(): TerminalSignalPermit? =
        synchronized(lifecycleMonitor) {
            if (disposed.get() || !permits.tryAcquire()) {
                return@synchronized null
            }
            Permit().also(activePermits::add)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun dispose() {
        val permitsToInvalidate = synchronized(lifecycleMonitor) {
            if (!disposed.compareAndSet(false, true)) {
                return
            }
            activePermits.toList()
        }
        try {
            permitsToInvalidate.forEach { permit ->
                try {
                    permit.invalidateFromDispatcher()
                } catch (error: Exception) {
                    Operators.onErrorDropped(error, Context.empty())
                }
            }
        } finally {
            lane.dispose()
        }
    }

    override fun isDisposed(): Boolean = disposed.get()

    private inner class Permit : TerminalSignalPermit {
        private val monitor = Any()
        private var state = TerminalSignalPermitState.ACTIVE
        private var queued: Runnable? = null
        private var dispatcherDisposed = false
        private var dispatcherDisposalAction: Runnable? = null

        @Suppress("TooGenericExceptionCaught")
        override fun dispatch(action: Runnable): Boolean {
            val schedulingFailure = synchronized(monitor) {
                if (state != TerminalSignalPermitState.ACTIVE) {
                    return false
                }
                state = TerminalSignalPermitState.DISPATCHED
                try {
                    val task = Runnable { runDispatched(action) }
                    queued = task
                    lane.execute(task)
                    return true
                } catch (error: Throwable) {
                    state = TerminalSignalPermitState.RELEASED
                    queued = null
                    dispatcherDisposalAction = null
                    error
                }
            }
            releasePermit(this)
            Exceptions.throwIfFatal(schedulingFailure)
            return false
        }

        override fun onDispatcherDisposed(action: Runnable) {
            val runImmediately = synchronized(monitor) {
                if (dispatcherDisposed) {
                    true
                } else {
                    dispatcherDisposalAction = action
                    false
                }
            }
            if (runImmediately) {
                action.run()
            }
        }

        override fun dispose() {
            var cancelledTask: Runnable? = null
            var shouldReleasePermit = false
            synchronized(monitor) {
                dispatcherDisposalAction = null
                when (state) {
                    TerminalSignalPermitState.ACTIVE -> {
                        state = TerminalSignalPermitState.RELEASED
                        shouldReleasePermit = true
                    }

                    TerminalSignalPermitState.DISPATCHED -> {
                        state = TerminalSignalPermitState.RELEASED
                        cancelledTask = queued
                        queued = null
                        shouldReleasePermit = true
                    }

                    TerminalSignalPermitState.RUNNING ->
                        state = TerminalSignalPermitState.CANCELLED_RUNNING

                    TerminalSignalPermitState.CANCELLED_RUNNING,
                    TerminalSignalPermitState.RELEASED,
                    -> Unit
                }
            }
            cancelledTask?.let(lane::cancel)
            if (shouldReleasePermit) {
                releasePermit(this)
            }
        }

        override fun isDisposed(): Boolean =
            synchronized(monitor) {
                state == TerminalSignalPermitState.CANCELLED_RUNNING ||
                    state == TerminalSignalPermitState.RELEASED
            }

        private fun runDispatched(action: Runnable) {
            val shouldRun = synchronized(monitor) {
                if (state == TerminalSignalPermitState.DISPATCHED) {
                    queued = null
                    state = TerminalSignalPermitState.RUNNING
                    true
                } else {
                    false
                }
            }
            if (!shouldRun) {
                return
            }
            try {
                action.run()
            } finally {
                synchronized(monitor) {
                    state = TerminalSignalPermitState.RELEASED
                    dispatcherDisposalAction = null
                }
                releasePermit(this)
            }
        }

        fun invalidateFromDispatcher() {
            var cancelledTask: Runnable? = null
            var shouldReleasePermit = false
            val disposalAction = synchronized(monitor) {
                dispatcherDisposed = true
                val action = dispatcherDisposalAction
                dispatcherDisposalAction = null
                when (state) {
                    TerminalSignalPermitState.ACTIVE -> {
                        state = TerminalSignalPermitState.RELEASED
                        shouldReleasePermit = true
                    }

                    TerminalSignalPermitState.DISPATCHED -> {
                        state = TerminalSignalPermitState.RELEASED
                        cancelledTask = queued
                        queued = null
                        shouldReleasePermit = true
                    }

                    TerminalSignalPermitState.RUNNING ->
                        state = TerminalSignalPermitState.CANCELLED_RUNNING

                    TerminalSignalPermitState.CANCELLED_RUNNING,
                    TerminalSignalPermitState.RELEASED,
                    -> Unit
                }
                action
            }
            cancelledTask?.let(lane::cancel)
            if (shouldReleasePermit) {
                releasePermit(this)
            }
            disposalAction?.run()
        }
    }

    private fun releasePermit(permit: Permit) {
        val removed = synchronized(lifecycleMonitor) {
            activePermits.remove(permit)
        }
        if (removed) {
            permits.release()
        }
    }
}

private enum class TerminalSignalPermitState {
    ACTIVE,
    DISPATCHED,
    RUNNING,
    CANCELLED_RUNNING,
    RELEASED,
}

private const val TERMINAL_SIGNAL_THREAD_CAP: Int = 8
private const val TERMINAL_SIGNAL_QUEUE_CAPACITY: Int = 256
