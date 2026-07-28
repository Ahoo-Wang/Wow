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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide bounded delivery boundary for runtime terminal subscribers.
 */
internal object RuntimeTerminationSignal {
    val dispatcher: RuntimeTerminationDispatcher =
        newRuntimeTerminationDispatcher("wow-runtime-termination")
    val controlDispatcher: RuntimeTerminationDispatcher =
        newRuntimeTerminationDispatcher(
            threadNamePrefix = "wow-runtime-termination-control",
            threadCap = TERMINATION_CONTROL_THREAD_CAP,
            queuedTaskCapacity = TERMINATION_CONTROL_QUEUE_CAPACITY,
        )
    val rejectedSubscriberCount = AtomicLong()
    val droppedNotificationCount = AtomicLong()
}

/**
 * Reserves terminal-delivery capacity when a subscriber is installed.
 *
 * The underlying task queue is used exclusively by this dispatcher. Therefore
 * an acquired permit guarantees one terminal task can be accepted later,
 * without executing user code on the runtime completion thread.
 */
internal interface RuntimeTerminationDispatcher : Disposable {
    fun tryAcquire(): RuntimeTerminationPermit?
}

internal interface RuntimeTerminationPermit : Disposable {
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
internal fun Mono<Void>.publishRuntimeTermination(
    dispatcher: RuntimeTerminationDispatcher = RuntimeTerminationSignal.dispatcher,
): Mono<Void> =
    RuntimeTerminationMono(this, dispatcher)

/**
 * Installs one trusted control-plane callback on an already isolated
 * dispatcher. Admission failure is reported to the claimant synchronously.
 */
@Suppress("TooGenericExceptionCaught")
internal fun Mono<Void>.subscribeRuntimeTermination(
    dispatcher: RuntimeTerminationDispatcher,
    onTermination: (Throwable?) -> Unit,
): Disposable {
    val permit = dispatcher.tryAcquire()
        ?: throw RejectedExecutionException(
            "The process-wide Wow runtime termination control dispatcher is saturated.",
        )
    val subscriber = RuntimeTerminationSubscriber(
        actual = RuntimeTerminationCallbackSubscriber(onTermination),
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

private class RuntimeTerminationMono(
    private val source: Mono<Void>,
    private val dispatcher: RuntimeTerminationDispatcher,
) : Mono<Void>() {
    @Suppress("TooGenericExceptionCaught")
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val permit = dispatcher.tryAcquire()
        if (permit == null) {
            RuntimeTerminationSignal.rejectedSubscriberCount.incrementAndGet()
            Operators.error(
                actual,
                RejectedExecutionException(
                    "The process-wide Wow runtime termination dispatcher is saturated.",
                ),
            )
            return
        }
        val subscriber = RuntimeTerminationSubscriber(actual, permit)
        try {
            source.subscribe(subscriber)
        } catch (error: Throwable) {
            subscriber.dispose()
            throw error
        }
    }
}

private class RuntimeTerminationSubscriber(
    actual: CoreSubscriber<in Void>,
    private val permit: RuntimeTerminationPermit,
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
        RuntimeTerminationSignal.droppedNotificationCount.incrementAndGet()
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

private class RuntimeTerminationCallbackSubscriber(
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
 * Creates a bounded daemon dispatcher whose logical queue is limited by
 * admission permits rather than by terminal-time rejection.
 */
internal fun newRuntimeTerminationDispatcher(
    threadNamePrefix: String,
    threadCap: Int = TERMINATION_THREAD_CAP,
    queuedTaskCapacity: Int = TERMINATION_QUEUE_CAPACITY,
): RuntimeTerminationDispatcher =
    BoundedRuntimeTerminationDispatcher(
        threadNamePrefix = threadNamePrefix,
        threadCap = threadCap,
        queuedTaskCapacity = queuedTaskCapacity,
    )

internal object ImmediateRuntimeTerminationDispatcher : RuntimeTerminationDispatcher {
    override fun tryAcquire(): RuntimeTerminationPermit =
        object : RuntimeTerminationPermit {
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

private class BoundedRuntimeTerminationDispatcher(
    threadNamePrefix: String,
    threadCap: Int,
    queuedTaskCapacity: Int,
) : RuntimeTerminationDispatcher {
    private val lifecycleMonitor = Any()
    private val disposed = AtomicBoolean()
    private val permits: Semaphore
    private val executor: ScheduledThreadPoolExecutor
    private val activePermits = mutableSetOf<Permit>()

    init {
        require(threadCap > 0) {
            "threadCap must be positive."
        }
        require(queuedTaskCapacity >= 0) {
            "queuedTaskCapacity must not be negative."
        }
        val capacity = Math.addExact(threadCap, queuedTaskCapacity)
        permits = Semaphore(capacity, true)
        executor = ScheduledThreadPoolExecutor(
            threadCap,
            RuntimeTerminationThreadFactory(threadNamePrefix),
        ).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(TERMINATION_THREAD_TTL_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }
    }

    override fun tryAcquire(): RuntimeTerminationPermit? =
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
            executor.shutdownNow()
        }
    }

    override fun isDisposed(): Boolean = disposed.get()

    private inner class Permit : RuntimeTerminationPermit {
        private val monitor = Any()
        private var state = RuntimeTerminationPermitState.ACTIVE
        private var future: ScheduledFuture<*>? = null
        private var dispatcherDisposed = false
        private var dispatcherDisposalAction: Runnable? = null

        @Suppress("TooGenericExceptionCaught")
        override fun dispatch(action: Runnable): Boolean {
            val schedulingFailure = synchronized(monitor) {
                if (state != RuntimeTerminationPermitState.ACTIVE) {
                    return false
                }
                state = RuntimeTerminationPermitState.DISPATCHED
                try {
                    future = executor.schedule(
                        { runDispatched(action) },
                        0,
                        TimeUnit.NANOSECONDS,
                    )
                    return true
                } catch (error: Throwable) {
                    state = RuntimeTerminationPermitState.RELEASED
                    future = null
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
            var cancelledFuture: ScheduledFuture<*>? = null
            var shouldReleasePermit = false
            synchronized(monitor) {
                dispatcherDisposalAction = null
                when (state) {
                    RuntimeTerminationPermitState.ACTIVE -> {
                        state = RuntimeTerminationPermitState.RELEASED
                        shouldReleasePermit = true
                    }

                    RuntimeTerminationPermitState.DISPATCHED -> {
                        state = RuntimeTerminationPermitState.RELEASED
                        cancelledFuture = future
                        future = null
                        shouldReleasePermit = true
                    }

                    RuntimeTerminationPermitState.RUNNING ->
                        state = RuntimeTerminationPermitState.CANCELLED_RUNNING

                    RuntimeTerminationPermitState.CANCELLED_RUNNING,
                    RuntimeTerminationPermitState.RELEASED,
                    -> Unit
                }
            }
            cancelledFuture?.cancel(false)
            if (shouldReleasePermit) {
                releasePermit(this)
            }
        }

        override fun isDisposed(): Boolean =
            synchronized(monitor) {
                state == RuntimeTerminationPermitState.CANCELLED_RUNNING ||
                    state == RuntimeTerminationPermitState.RELEASED
            }

        private fun runDispatched(action: Runnable) {
            val shouldRun = synchronized(monitor) {
                if (state == RuntimeTerminationPermitState.DISPATCHED) {
                    state = RuntimeTerminationPermitState.RUNNING
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
                    state = RuntimeTerminationPermitState.RELEASED
                    future = null
                    dispatcherDisposalAction = null
                }
                releasePermit(this)
            }
        }

        fun invalidateFromDispatcher() {
            var cancelledFuture: ScheduledFuture<*>? = null
            var shouldReleasePermit = false
            val disposalAction = synchronized(monitor) {
                dispatcherDisposed = true
                val action = dispatcherDisposalAction
                dispatcherDisposalAction = null
                when (state) {
                    RuntimeTerminationPermitState.ACTIVE -> {
                        state = RuntimeTerminationPermitState.RELEASED
                        shouldReleasePermit = true
                    }

                    RuntimeTerminationPermitState.DISPATCHED -> {
                        state = RuntimeTerminationPermitState.RELEASED
                        cancelledFuture = future
                        future = null
                        shouldReleasePermit = true
                    }

                    RuntimeTerminationPermitState.RUNNING ->
                        state = RuntimeTerminationPermitState.CANCELLED_RUNNING

                    RuntimeTerminationPermitState.CANCELLED_RUNNING,
                    RuntimeTerminationPermitState.RELEASED,
                    -> Unit
                }
                action
            }
            cancelledFuture?.cancel(false)
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

private enum class RuntimeTerminationPermitState {
    ACTIVE,
    DISPATCHED,
    RUNNING,
    CANCELLED_RUNNING,
    RELEASED,
}

private class RuntimeTerminationThreadFactory(
    private val threadNamePrefix: String,
) : ThreadFactory {
    private val threadId = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(
            runnable,
            "$threadNamePrefix-${threadId.incrementAndGet()}",
        ).apply {
            isDaemon = true
        }
}

private const val TERMINATION_THREAD_CAP: Int = 8
private const val TERMINATION_QUEUE_CAPACITY: Int = 256
private const val TERMINATION_CONTROL_THREAD_CAP: Int = 4
private const val TERMINATION_CONTROL_QUEUE_CAPACITY: Int = 256
private const val TERMINATION_THREAD_TTL_SECONDS: Long = 60
