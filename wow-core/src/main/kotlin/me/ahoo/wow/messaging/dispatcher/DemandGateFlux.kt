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

package me.ahoo.wow.messaging.dispatcher

import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Operators
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Subscribes to the source immediately while holding demand behind an explicit barrier.
 */
internal class DemandGateFlux<T : Any>(
    private val source: Flux<T>,
    private val detachedCancellationDispatcher: ((() -> Unit) -> Unit) = { cancellation ->
        cancellation()
    },
) : Flux<T>() {
    private val opened = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val gateSubscriber = AtomicReference<GateSubscriber<T>?>()

    override fun subscribe(actual: CoreSubscriber<in T>) {
        val gate = GateSubscriber(
            actual = actual,
            initiallyOpen = opened.get(),
            initiallyCancelled = cancelled.get(),
            detachedCancellationDispatcher = detachedCancellationDispatcher,
        )
        check(gateSubscriber.compareAndSet(null, gate)) {
            "DemandGateFlux supports exactly one subscriber."
        }
        if (cancelled.get()) {
            gate.detachCancellation()
        }
        source.subscribe(gate)
        if (opened.get()) {
            gate.open()
        }
    }

    fun open() {
        if (opened.compareAndSet(false, true)) {
            gateSubscriber.get()?.open()
        }
    }

    /**
     * Atomically closes intake without invoking the source's cancellation hook.
     *
     * The returned action is deliberately detached from this gate and may be
     * executed on a bounded cleanup executor. This lets force-stop establish a
     * prompt logical boundary even when user-provided cancellation code blocks.
     */
    fun detachCancellation(): (() -> Unit)? =
        if (cancelled.compareAndSet(false, true)) {
            gateSubscriber.get()?.detachCancellation()
        } else {
            null
        }

    private class GateSubscriber<T : Any>(
        private val actual: CoreSubscriber<in T>,
        initiallyOpen: Boolean,
        initiallyCancelled: Boolean,
        private val detachedCancellationDispatcher: ((() -> Unit) -> Unit),
    ) : CoreSubscriber<T>, Subscription {
        private val monitor = Any()
        private var upstream: Subscription? = null
        private var pendingRequest = 0L
        private var open = initiallyOpen
        private var cancelled = initiallyCancelled
        private var terminated = false

        override fun currentContext() = actual.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            var notifyDownstream = false
            val dispatchCancellation = synchronized(monitor) {
                if (upstream != null) {
                    true
                } else {
                    upstream = subscription
                    notifyDownstream = true
                    cancelled
                }
            }
            if (notifyDownstream) {
                actual.onSubscribe(
                    if (dispatchCancellation) Operators.cancelledSubscription() else this,
                )
            }
            if (dispatchCancellation) {
                detachedCancellationDispatcher(subscription::cancel)
            }
        }

        override fun request(n: Long) {
            if (!Operators.validate(n)) {
                return
            }
            val requestNow = synchronized(monitor) {
                if (cancelled || terminated) {
                    null
                } else if (open) {
                    upstream to n
                } else {
                    pendingRequest = addCap(pendingRequest, n)
                    null
                }
            }
            requestNow?.first?.request(requestNow.second)
        }

        override fun cancel() {
            detachCancellation()?.invoke()
        }

        fun detachCancellation(): (() -> Unit)? {
            val subscription = synchronized(monitor) {
                if (cancelled) {
                    null
                } else {
                    cancelled = true
                    upstream
                }
            }
            return subscription?.let {
                {
                    it.cancel()
                }
            }
        }

        fun open() {
            val requestNow = synchronized(monitor) {
                if (open || cancelled || terminated) {
                    null
                } else {
                    open = true
                    val requested = pendingRequest
                    pendingRequest = 0
                    if (requested == 0L) null else upstream to requested
                }
            }
            requestNow?.first?.request(requestNow.second)
        }

        override fun onNext(value: T) {
            val deliver = synchronized(monitor) {
                !cancelled && !terminated
            }
            if (deliver) {
                actual.onNext(value)
            }
        }

        override fun onError(error: Throwable) {
            if (markTerminated()) {
                actual.onError(error)
            }
        }

        override fun onComplete() {
            if (markTerminated()) {
                actual.onComplete()
            }
        }

        private fun markTerminated(): Boolean =
            synchronized(monitor) {
                if (cancelled || terminated) {
                    false
                } else {
                    terminated = true
                    pendingRequest = 0
                    true
                }
            }

        private fun addCap(current: Long, toAdd: Long): Long {
            val sum = current + toAdd
            return if (sum < 0L) Long.MAX_VALUE else sum
        }
    }
}
