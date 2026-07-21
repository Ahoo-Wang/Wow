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

package me.ahoo.wow.opentelemetry

import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import java.util.concurrent.atomic.AtomicBoolean

open class TraceSubscriber<T : Any, O : Any>(
    private val instrumenter: Instrumenter<T, Unit>,
    private val otelContext: Context,
    private val request: T,
    protected val actual: CoreSubscriber<in O>
) : CoreSubscriber<O> {
    private val ended = AtomicBoolean()

    override fun currentContext(): reactor.util.context.Context {
        return ReactorTraceContext.set(actual.currentContext(), otelContext)
    }

    override fun onSubscribe(subscription: Subscription) {
        withContext {
            actual.onSubscribe(TraceSubscription(subscription))
        }
    }

    override fun onNext(signal: O) {
        withContext {
            actual.onNext(signal)
        }
    }

    override fun onError(throwable: Throwable) {
        terminate(throwable) {
            actual.onError(throwable)
        }
    }

    override fun onComplete() {
        complete(null)
    }

    protected fun complete(error: Throwable?) {
        terminate(error) {
            actual.onComplete()
        }
    }

    internal fun endSpan(error: Throwable?): Boolean {
        if (!ended.compareAndSet(false, true)) {
            return false
        }
        instrumenter.end(otelContext, request, null, error)
        return true
    }

    private fun terminate(error: Throwable?, signal: () -> Unit) {
        if (!endSpan(error)) {
            return
        }
        withContext(signal)
    }

    private fun withContext(block: () -> Unit) {
        otelContext.makeCurrent().use {
            block()
        }
    }

    private inner class TraceSubscription(
        private val delegate: Subscription,
    ) : Subscription {
        override fun request(n: Long) {
            withContext {
                delegate.request(n)
            }
        }

        override fun cancel() {
            try {
                withContext {
                    delegate.cancel()
                }
            } finally {
                endSpan(null)
            }
        }
    }
}
