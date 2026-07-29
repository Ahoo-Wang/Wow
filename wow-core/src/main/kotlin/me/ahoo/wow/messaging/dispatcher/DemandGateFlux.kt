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

/**
 * Subscribes immediately while withholding demand until [open].
 *
 * [close] stops source intake and completes the downstream so already admitted
 * work can drain. It deliberately does not cancel downstream handlers.
 */
internal class DemandGateFlux<T : Any>(
    private val source: Flux<T>,
) : Flux<T>() {
    private enum class State {
        WAITING,
        OPEN,
        CLOSED,
    }

    private val monitor = Any()
    private var state = State.WAITING
    private var upstream: Subscription? = null
    private var downstream: CoreSubscriber<in T>? = null
    private var pendingDemand = 0L
    private var subscribed = false
    private var downstreamReady = false
    private var terminalDelivered = false

    override fun subscribe(actual: CoreSubscriber<in T>) {
        val serialized = Operators.serialize(actual)
        synchronized(monitor) {
            check(!subscribed) {
                "DemandGateFlux supports exactly one subscription."
            }
            subscribed = true
            downstream = serialized
        }
        source.subscribe(GateSubscriber(serialized))
    }

    fun open() {
        val request = synchronized(monitor) {
            check(subscribed) {
                "DemandGateFlux must be subscribed before it is opened."
            }
            if (state != State.WAITING) {
                return
            }
            state = State.OPEN
            pendingDemand.also {
                pendingDemand = 0
            }
        }
        if (request > 0) {
            upstream?.request(request)
        }
    }

    fun close() {
        val terminal = synchronized(monitor) {
            if (state == State.CLOSED) {
                return
            }
            state = State.CLOSED
            val subscription = upstream
            val subscriber = if (downstreamReady && !terminalDelivered) {
                terminalDelivered = true
                downstream
            } else {
                null
            }
            pendingDemand = 0
            Pair(subscription, subscriber)
        }
        terminal.first?.cancel()
        terminal.second?.onComplete()
    }

    private inner class GateSubscriber(
        private val actual: CoreSubscriber<in T>,
    ) : CoreSubscriber<T> {
        override fun currentContext() = actual.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            val accepted = synchronized(monitor) {
                if (upstream != null) {
                    false
                } else {
                    upstream = subscription
                    true
                }
            }
            if (!accepted) {
                subscription.cancel()
                return
            }
            val closed = synchronized(monitor) {
                state == State.CLOSED
            }
            if (closed) {
                subscription.cancel()
                actual.onSubscribe(Operators.emptySubscription())
            } else {
                actual.onSubscribe(GateSubscription(subscription))
            }
            val complete = synchronized(monitor) {
                downstreamReady = true
                if (state == State.CLOSED && !terminalDelivered) {
                    terminalDelivered = true
                    true
                } else {
                    false
                }
            }
            if (complete) {
                actual.onComplete()
            }
        }

        override fun onNext(value: T) {
            val emit = synchronized(monitor) {
                state != State.CLOSED
            }
            if (emit) {
                actual.onNext(value)
            } else {
                Operators.onDiscard(value, currentContext())
            }
        }

        override fun onError(error: Throwable) {
            val emit = synchronized(monitor) {
                if (state == State.CLOSED) {
                    false
                } else {
                    state = State.CLOSED
                    terminalDelivered = true
                    true
                }
            }
            if (emit) {
                actual.onError(error)
            }
        }

        override fun onComplete() {
            val emit = synchronized(monitor) {
                if (state == State.CLOSED) {
                    false
                } else {
                    state = State.CLOSED
                    terminalDelivered = true
                    true
                }
            }
            if (emit) {
                actual.onComplete()
            }
        }
    }

    private inner class GateSubscription(
        private val subscription: Subscription,
    ) : Subscription {
        override fun request(amount: Long) {
            if (!Operators.validate(amount)) {
                return
            }
            val requestNow = synchronized(monitor) {
                when (state) {
                    State.WAITING -> {
                        pendingDemand = Operators.addCap(pendingDemand, amount)
                        false
                    }

                    State.OPEN -> true
                    State.CLOSED -> false
                }
            }
            if (requestNow) {
                subscription.request(amount)
            }
        }

        override fun cancel() {
            synchronized(monitor) {
                state = State.CLOSED
                pendingDemand = 0
                terminalDelivered = true
            }
            subscription.cancel()
        }
    }
}
