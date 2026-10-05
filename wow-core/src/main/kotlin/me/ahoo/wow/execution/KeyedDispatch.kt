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

import kotlinx.coroutines.CoroutineDispatcher
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Operators
import reactor.util.context.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

/**
 * The Reactor context key under which a dispatcher's handler finds the [CoroutineDispatcher] its `suspend` and `Flow`
 * functions resume on.
 */
internal object KeyedExecutorContext {
    val COROUTINE_DISPATCHER_KEY: Any = CoroutineDispatcher::class

    fun coroutineDispatcherOf(context: reactor.util.context.ContextView): CoroutineDispatcher? =
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
    private val handler: (T) -> Mono<Void>,
) : CoreSubscriber<T>, Subscription {
    private val maxInFlight = executor.maxInFlight
    private val replenishThreshold = maxOf(1, maxInFlight - (maxInFlight shr 2))
    private val mailboxes = ConcurrentHashMap<Any, Mailbox>()
    private val mailboxFactory = Function<Any, Mailbox> { Mailbox(it) }
    private val inFlight = AtomicInteger()
    private val finishedSinceRequest = AtomicInteger()
    private val terminated = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val context: Context = actual.currentContext()
    private val handlerContext: Context =
        context.put(KeyedExecutorContext.COROUTINE_DISPATCHER_KEY, executor.coroutineDispatcher)
    private lateinit var upstream: Subscription

    @Volatile
    private var done = false

    @Volatile
    private var cancelled = false

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
            val start = synchronized(mailbox) {
                if (mailbox.removed) {
                    null
                } else {
                    mailbox.queue.addLast(element)
                    mailbox.queue.size == 1
                }
            } ?: continue
            if (start) {
                schedule(mailbox, element)
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

    private fun schedule(mailbox: Mailbox, element: T) {
        try {
            executor.scheduler.schedule { run(mailbox, element) }
        } catch (rejected: RejectedExecutionException) {
            fail(rejected)
            var discarded: T? = element
            while (discarded != null) {
                Operators.onDiscard(discarded, context)
                discarded = finish(mailbox)
            }
        }
    }

    /** Runs [first] and then, while they complete synchronously, the mailbox's next elements, up to a fair budget. */
    private fun run(mailbox: Mailbox, first: T) {
        var element = first
        var budget = INLINE_BUDGET
        while (true) {
            if (cancelled) {
                Operators.onDiscard(element, context)
                element = finish(mailbox) ?: return
                continue
            }
            val inner = Inner(mailbox)
            synchronized(mailbox) { mailbox.current = inner }
            val publisher = try {
                handler(element)
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                Mono.error(error)
            }
            publisher.subscribe(inner)
            if (cancelled) {
                inner.cancel()
            }
            if (!inner.continueInline()) {
                return
            }
            val next = finish(mailbox) ?: return
            budget--
            if (budget == 0) {
                schedule(mailbox, next)
                return
            }
            element = next
        }
    }

    /** Called when [mailbox]'s head finishes asynchronously. */
    private fun finishedAsync(mailbox: Mailbox) {
        val next = finish(mailbox) ?: return
        schedule(mailbox, next)
    }

    /**
     * Removes [mailbox]'s finished head and accounts for it; returns the next element to run, or `null` when the
     * mailbox is empty (it is then removed, so a later element with its key opens a fresh one).
     */
    private fun finish(mailbox: Mailbox): T? {
        val next = synchronized(mailbox) {
            mailbox.current = null
            mailbox.queue.removeFirst()
            mailbox.queue.firstOrNull().also {
                if (it == null) {
                    mailbox.removed = true
                    mailboxes.remove(mailbox.key, mailbox)
                }
            }
        }
        val remaining = inFlight.decrementAndGet()
        if (remaining == 0 && done) {
            completeIfIdle()
        } else if (!done && !cancelled && finishedSinceRequest.incrementAndGet() >= replenishThreshold) {
            val requested = finishedSinceRequest.getAndSet(0)
            if (requested > 0) {
                upstream.request(requested.toLong())
            }
        }
        return next
    }

    private fun completeIfIdle() {
        if (!cancelled && inFlight.get() == 0 && terminated.compareAndSet(false, true)) {
            actual.onComplete()
        }
    }

    private fun fail(error: Throwable) {
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
            val (running, queued) = synchronized(mailbox) {
                val waiting = if (mailbox.queue.size > 1) {
                    val head = mailbox.queue.removeFirst()
                    mailbox.queue.toList().also {
                        inFlight.addAndGet(-it.size)
                        mailbox.queue.clear()
                        mailbox.queue.addLast(head)
                    }
                } else {
                    emptyList()
                }
                mailbox.current to waiting
            }
            queued.forEach { Operators.onDiscard(it, context) }
            running?.cancel()
        }
    }

    private inner class Mailbox(val key: Any) {
        /** Unfinished elements; the head is running (or scheduled to run). Guarded by this mailbox. */
        val queue = ArrayDeque<T>(2)

        /** Set once the mailbox is empty and has left [mailboxes]. Guarded by this mailbox. */
        var removed = false

        /** The running head's subscriber. Guarded by this mailbox. */
        var current: Inner? = null
    }

    private inner class Inner(private val mailbox: Mailbox) : CoreSubscriber<Void> {
        private val subscription = AtomicReference<Subscription?>()

        /** [SUBSCRIBING] until `subscribe` returns; then [COMPLETED_INLINE] or [ASYNC]. */
        private val state = AtomicInteger(SUBSCRIBING)

        override fun currentContext(): Context = handlerContext

        override fun onSubscribe(s: Subscription) {
            if (subscription.compareAndSet(null, s)) {
                s.request(Long.MAX_VALUE)
            } else {
                s.cancel()
            }
        }

        override fun onNext(t: Void) = Unit

        override fun onError(error: Throwable) {
            fail(error)
            onComplete()
        }

        override fun onComplete() {
            if (!state.compareAndSet(SUBSCRIBING, COMPLETED_INLINE)) {
                finishedAsync(mailbox)
            }
        }

        /** Whether the handler completed while being subscribed, so the caller continues with the next element. */
        fun continueInline(): Boolean = !state.compareAndSet(SUBSCRIBING, ASYNC)

        fun cancel() {
            subscription.getAndSet(CANCELLED)?.cancel()
        }
    }

    private companion object {
        const val INLINE_BUDGET = 64
        const val SUBSCRIBING = 0
        const val COMPLETED_INLINE = 1
        const val ASYNC = 2
        val CANCELLED: Subscription = Operators.emptySubscription()
    }
}
