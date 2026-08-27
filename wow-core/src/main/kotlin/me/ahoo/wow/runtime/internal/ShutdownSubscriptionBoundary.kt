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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.util.context.Context

internal fun interface RuntimeCleanupDispatcher {
    fun dispatch(action: Runnable): Boolean
}

internal enum class CleanupDispatchResult {
    NO_UPSTREAM,
    DISPATCHED,
    REJECTED,
}

/**
 * Opaque boundary between the shutdown publisher and its runtime owner.
 *
 * [detach] synchronously clears every callback that can reach the owning
 * runtime. Only the opaque upstream [Subscription] is handed to bounded,
 * best-effort cleanup. A late signal can therefore never re-enter a detached
 * runtime even when physical cancellation is rejected or blocks forever.
 */
internal class ShutdownSubscriptionBoundary(
    private val cleanupDispatcher: RuntimeCleanupDispatcher,
    onComplete: () -> Unit,
    onError: (Throwable) -> Unit,
) : CoreSubscriber<Void> {
    private companion object {
        val log = KotlinLogging.logger {}
    }

    private data class Callbacks(
        val onComplete: () -> Unit,
        val onError: (Throwable) -> Unit,
    )

    private val monitor = Any()
    private var callbacks: Callbacks? = Callbacks(onComplete, onError)
    private var upstream: Subscription? = null
    private var detached = false

    override fun currentContext(): Context = Context.empty()

    override fun onSubscribe(subscription: Subscription) {
        val accepted = synchronized(monitor) {
            if (detached || upstream != null) {
                false
            } else {
                upstream = subscription
                true
            }
        }
        if (!accepted) {
            dispatchCancellation(subscription)
            return
        }
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(value: Void) = Unit

    override fun onError(throwable: Throwable) {
        val terminalCallbacks = synchronized(monitor) {
            if (detached) {
                return
            }
            val claimedCallbacks = callbacks ?: return
            callbacks = null
            upstream = null
            claimedCallbacks
        }
        terminalCallbacks.onError(throwable)
    }

    override fun onComplete() {
        val terminalCallbacks = synchronized(monitor) {
            if (detached) {
                return
            }
            val claimedCallbacks = callbacks ?: return
            callbacks = null
            upstream = null
            claimedCallbacks
        }
        terminalCallbacks.onComplete()
    }

    fun detach(): CleanupDispatchResult {
        val subscription = synchronized(monitor) {
            if (detached) {
                return CleanupDispatchResult.NO_UPSTREAM
            }
            detached = true
            callbacks = null
            upstream.also {
                upstream = null
            }
        } ?: return CleanupDispatchResult.NO_UPSTREAM
        return dispatchCancellation(subscription)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchCancellation(subscription: Subscription): CleanupDispatchResult {
        val dispatched = try {
            cleanupDispatcher.dispatch(Runnable(subscription::cancel))
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            log.warn(error) {
                "Runtime shutdown upstream cancellation dispatch failed; " +
                    "runtime callbacks were already detached."
            }
            return CleanupDispatchResult.REJECTED
        }
        if (!dispatched) {
            log.warn {
                "Runtime shutdown upstream cancellation was rejected; " +
                    "runtime callbacks were already detached."
            }
            return CleanupDispatchResult.REJECTED
        }
        return CleanupDispatchResult.DISPATCHED
    }
}
