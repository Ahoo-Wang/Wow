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

package me.ahoo.wow.execution

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Operators
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import java.util.function.Function

private val log = KotlinLogging.logger {}

/**
 * The Reactor context key under which a dispatcher's handler finds the [CoroutineDispatcher] its `suspend` and `Flow`
 * functions resume on.
 */
internal object KeyedExecutorContext {
    val COROUTINE_DISPATCHER_KEY: Any = CoroutineDispatcher::class

    fun coroutineDispatcherOf(context: ContextView): CoroutineDispatcher? =
        context.getOrDefault<CoroutineDispatcher>(COROUTINE_DISPATCHER_KEY, null)
}

/**
 * Runs [handler] for every element of this flux on [executor]'s workers, one mailbox per [keyOf] key: elements with
 * one key run one at a time in arrival order, elements with different keys run in parallel. At most
 * [KeyedExecutor.maxInFlight] elements are unfinished at a time; demand is replenished as they finish.
 *
 * The returned [Mono] completes once the source has completed and every accepted element has finished. A source error
 * or a handler error cancels the source and the running handlers and fails it; elements not yet started are discarded
 * through the subscriber context's discard hook ([Flux.doOnDiscard]), as are elements arriving after cancellation.
 */
internal fun <T : Any> Flux<T>.dispatchKeyed(
    executor: KeyedExecutor,
    keyOf: (T) -> Any,
    handler: (T) -> Mono<Void>,
): Mono<Void> = KeyedDispatchMono(this, executor, keyOf, handler)

private class KeyedDispatchMono<T : Any>(
    private val source: Flux<T>,
    private val executor: KeyedExecutor,
    private val keyOf: (T) -> Any,
    private val handler: (T) -> Mono<Void>,
) : Mono<Void>() {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        source.subscribe(KeyedDispatchSubscriber(actual, executor, keyOf, handler))
    }
}

@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
private class KeyedDispatchSubscriber<T : Any>(
    private val actual: CoreSubscriber<in Void>,
    private val executor: KeyedExecutor,
    private val keyOf: (T) -> Any,
    val handler: (T) -> Mono<Void>,
) : CoreSubscriber<T>, Subscription {
    private val maxInFlight = executor.maxInFlight

    /**
     * Demand is replenished in small batches: messages that stay unfinished for long (a slow aggregate's I/O) hold
     * part of the window, so waiting for three quarters of it to finish (Reactor's usual low tide) would stall the
     * other aggregates' messages in the transport for as long as the slow ones take.
     */
    private val replenishThreshold = maxOf(1, maxInFlight shr REPLENISH_SHIFT)
    val mailboxes = ConcurrentHashMap<Any, Mailbox<T>>()
    private val mailboxFactory = Function<Any, Mailbox<T>> { Mailbox(it, this) }
    private val inFlight = AtomicInteger()
    private val finishedSinceRequest = AtomicInteger()
    private val terminated = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    val context: Context = actual.currentContext()
    val handlerContext: Context =
        context.put(KeyedExecutorContext.COROUTINE_DISPATCHER_KEY, executor.coroutineDispatcher)
    private lateinit var upstream: Subscription

    @Volatile
    private var done = false

    @Volatile
    var cancelled = false
        private set

    override fun currentContext(): Context = context

    override fun onSubscribe(subscription: Subscription) {
        if (Operators.validate(if (::upstream.isInitialized) upstream else null, subscription)) {
            upstream = subscription
            actual.onSubscribe(this)
            subscription.request(maxInFlight.toLong())
        }
    }

    override fun onNext(element: T) {
        if (done || cancelled) {
            Operators.onDiscard(element, context)
            return
        }
        val key = try {
            keyOf(element)
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            Operators.onDiscard(element, context)
            fail(error)
            return
        }
        inFlight.incrementAndGet()
        while (true) {
            val mailbox = mailboxes.computeIfAbsent(key, mailboxFactory)
            when (mailbox.offer(element)) {
                Mailbox.Offer.REMOVED -> continue
                Mailbox.Offer.STARTED -> schedule(mailbox)
                Mailbox.Offer.QUEUED -> Unit
            }
            return
        }
    }

    override fun onError(error: Throwable) {
        if (done) {
            Operators.onErrorDropped(error, context)
            return
        }
        done = true
        fail(error)
    }

    override fun onComplete() {
        if (done) {
            return
        }
        done = true
        completeIfIdle()
    }

    override fun request(n: Long) = Unit

    override fun cancel() {
        if (cancelled) {
            return
        }
        cancelled = true
        upstream.cancel()
        cancelMailboxes()
    }

    fun nextAffinity(): Int = executor.dispatchWorkers.nextAffinity()

    /** Whether no further element may start: the dispatch is cancelled or the workers are force-closed. */
    val stopped: Boolean
        get() = cancelled || executor.dispatchWorkers.forced

    private companion object {
        /** Replenish once 1/16 of the in-flight window has finished. */
        const val REPLENISH_SHIFT = 4
    }

    /** Synchronously completing elements one mailbox runs per turn before it yields its worker. */
    val throughput = executor.throughput

    /**
     * Makes [mailbox]'s head runnable on a worker. Only the holder of the mailbox's run right calls this, so a
     * mailbox is queued at most once; [Mailbox.markScheduled] asserts it.
     */
    fun schedule(mailbox: Mailbox<T>) {
        if (!mailbox.markScheduled()) {
            fail(IllegalStateException("Mailbox[${mailbox.key}] was scheduled twice."))
            return
        }
        try {
            executor.dispatchWorkers.execute(mailbox, mailbox.affinity)
        } catch (rejected: RejectedExecutionException) {
            mailbox.clearScheduled()
            fail(rejected)
            mailbox.discardAll()
        }
    }

    fun discard(element: T) {
        Operators.onDiscard(element, context)
    }

    /** Accounts for one finished (or discarded) element: replenishes demand, completes when the source is done. */
    fun finished() {
        val remaining = inFlight.decrementAndGet()
        if (remaining == 0 && done) {
            completeIfIdle()
        } else if (!done && !cancelled && finishedSinceRequest.incrementAndGet() >= replenishThreshold) {
            val requested = finishedSinceRequest.getAndSet(0)
            if (requested > 0) {
                upstream.request(requested.toLong())
            }
        }
    }

    private fun completeIfIdle() {
        if (!cancelled && inFlight.get() == 0 && terminated.compareAndSet(false, true)) {
            actual.onComplete()
        }
    }

    fun fail(error: Throwable) {
        if (!failure.compareAndSet(null, error)) {
            Operators.onErrorDropped(error, context)
            return
        }
        if (!cancelled) {
            cancelled = true
            if (::upstream.isInitialized) {
                upstream.cancel()
            }
            cancelMailboxes()
        }
        if (terminated.compareAndSet(false, true)) {
            actual.onError(error)
        }
    }

    /**
     * Discards every queued element behind a mailbox head and cancels the running heads; a head scheduled but not yet
     * running is discarded when it runs.
     */
    private fun cancelMailboxes() {
        mailboxes.values.forEach { mailbox ->
            val queued = mailbox.drainQueued()
            inFlight.addAndGet(-queued.size)
            queued.forEach(::discard)
            mailbox.cancelRunning()
        }
    }

    /**
     * The elements of one key. The head runs (or is scheduled to run); the rest wait in [queue], created only when a
     * key has more than one unfinished element. Only the holder of the run right — whoever made the head runnable —
     * runs or schedules it, so at most one element of a key is active. A mailbox is its own [Runnable] (its worker runs
     * its turn) and its own subscriber of the head's handler, so a turn allocates nothing per element.
     */
    @Suppress("TooManyFunctions")
    class Mailbox<T : Any>(
        val key: Any,
        private val owner: KeyedDispatchSubscriber<T>,
    ) : Runnable, CoreSubscriber<Void>, DispatchWorkers.Discardable {
        /**
         * The worker this mailbox runs on for as long as it exists. There is no work stealing: a mailbox never moves
         * to another worker, even when its worker is busy with other mailboxes.
         */
        val affinity: Int = owner.nextAffinity()

        enum class Offer { STARTED, QUEUED, REMOVED }

        /** Guarded by this mailbox. */
        private var head: T? = null

        /** Guarded by this mailbox. */
        private var queue: ArrayDeque<T>? = null

        /** Set once the mailbox is empty and has left [KeyedDispatchSubscriber.mailboxes]. Guarded by this mailbox. */
        private var removed = false

        /** [SUBSCRIBING] while the head's handler is being subscribed; then [COMPLETED_INLINE] or [ASYNC]. */
        @Volatile
        @JvmField
        var phase: Int = SUBSCRIBING

        /** 1 while the mailbox waits in its worker's queue: it is there at most once. */
        @Volatile
        @JvmField
        var scheduled: Int = 0

        fun markScheduled(): Boolean = SCHEDULED.compareAndSet(this, 0, 1)

        fun clearScheduled() {
            scheduled = 0
        }

        /** The running head's subscription, or [CANCELLED]. */
        @Volatile
        @JvmField
        var subscription: Subscription? = null

        fun offer(element: T): Offer =
            synchronized(this) {
                when {
                    removed -> Offer.REMOVED
                    head == null -> {
                        head = element
                        Offer.STARTED
                    }

                    else -> {
                        (queue ?: ArrayDeque<T>(INITIAL_QUEUE_CAPACITY).also { queue = it }).addLast(element)
                        Offer.QUEUED
                    }
                }
            }

        /** Runs the head, then — while handlers complete synchronously — the next elements, up to a fair budget. */
        override fun run() {
            clearScheduled()
            var budget = owner.throughput
            while (true) {
                val element = synchronized(this) { head } ?: return
                if (owner.stopped) {
                    owner.discard(element)
                    if (!advance()) {
                        return
                    }
                    continue
                }
                phase = SUBSCRIBING
                subscription = null
                val publisher = try {
                    owner.handler(element)
                } catch (error: Throwable) {
                    Mono.error(containFatal(error))
                }
                try {
                    publisher.subscribe(this)
                } catch (error: Throwable) {
                    // Thrown out of subscribe (Reactor rethrows JVM-fatal errors): fail the dispatch as a handler error
                    // does and finish the head here, so neither the mailbox nor its worker is left wedged. The handler
                    // may not have signalled, so the element is also discarded: the discard hook releases its resources.
                    owner.fail(containFatal(error))
                    cancelRunning()
                    owner.discard(element)
                    PHASE.compareAndSet(this, SUBSCRIBING, COMPLETED_INLINE)
                }
                if (owner.cancelled) {
                    cancelRunning()
                }
                if (PHASE.compareAndSet(this, SUBSCRIBING, ASYNC)) {
                    return
                }
                if (!advance()) {
                    return
                }
                budget--
                if (budget == 0) {
                    owner.schedule(this)
                    return
                }
            }
        }

        /**
         * A fatal error (JVM-fatal, or one Reactor bubbles) is logged and wrapped, so it fails the dispatch as an
         * ordinary handler error: the runtime's own failure handling rethrows fatal errors, which would leave its
         * shutdown half done.
         */
        private fun containFatal(error: Throwable): Throwable {
            if (!Exceptions.isFatal(error)) {
                return error
            }
            log.error(error) { "Mailbox[$key] handler threw a fatal error: its dispatch fails." }
            return IllegalStateException("Mailbox[$key] handler threw a fatal error.", error)
        }

        /** Finishes the head; returns whether a next element became the head (else the mailbox is removed). */
        private fun advance(): Boolean {
            val hasNext = synchronized(this) {
                val next = queue?.removeFirstOrNull()
                head = next
                if (next == null) {
                    removed = true
                    owner.mailboxes.remove(key, this)
                }
                next != null
            }
            owner.finished()
            return hasNext
        }

        /** Removes the waiting elements (not the head) and returns them. */
        fun drainQueued(): List<T> =
            synchronized(this) {
                queue?.toList()?.also { queue?.clear() } ?: emptyList()
            }

        /** The workers were force-closed while this mailbox was queued: discard instead of run. */
        override fun discard() {
            clearScheduled()
            discardAll()
        }

        /** Discards the head and every waiting element, finishing each (the executor rejected the run). */
        fun discardAll() {
            do {
                val element = synchronized(this) { head } ?: return
                owner.discard(element)
            } while (advance())
        }

        fun cancelRunning() {
            SUBSCRIPTION.getAndSet(this, CANCELLED)?.cancel()
        }

        override fun currentContext(): Context = owner.handlerContext

        override fun onSubscribe(s: Subscription) {
            if (SUBSCRIPTION.compareAndSet(this, null, s)) {
                s.request(Long.MAX_VALUE)
            } else {
                s.cancel()
            }
        }

        override fun onNext(t: Void) = Unit

        override fun onError(error: Throwable) {
            owner.fail(containFatal(error))
            onComplete()
        }

        override fun onComplete() {
            if (PHASE.compareAndSet(this, SUBSCRIBING, COMPLETED_INLINE)) {
                return
            }
            if (advance()) {
                owner.schedule(this)
            }
        }

        private companion object {
            const val SUBSCRIBING = 0
            const val COMPLETED_INLINE = 1
            const val ASYNC = 2
            const val INITIAL_QUEUE_CAPACITY = 4
            val CANCELLED: Subscription = Operators.emptySubscription()
            val PHASE: AtomicIntegerFieldUpdater<Mailbox<*>> =
                AtomicIntegerFieldUpdater.newUpdater(Mailbox::class.java, "phase")
            val SCHEDULED: AtomicIntegerFieldUpdater<Mailbox<*>> =
                AtomicIntegerFieldUpdater.newUpdater(Mailbox::class.java, "scheduled")

            @Suppress("UNCHECKED_CAST")
            val SUBSCRIPTION: AtomicReferenceFieldUpdater<Mailbox<*>, Subscription?> =
                AtomicReferenceFieldUpdater.newUpdater(
                    Mailbox::class.java as Class<Mailbox<*>>,
                    Subscription::class.java,
                    "subscription",
                ) as AtomicReferenceFieldUpdater<Mailbox<*>, Subscription?>
        }
    }
}
