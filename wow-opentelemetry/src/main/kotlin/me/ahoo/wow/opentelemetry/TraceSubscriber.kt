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

open class TraceSubscriber<T : Any, O : Any>(
    private val instrumenter: Instrumenter<T, Unit>,
    private val otelContext: Context,
    private val request: T,
    private val actual: CoreSubscriber<in O>
) : CoreSubscriber<O> {
    override fun currentContext(): reactor.util.context.Context {
        return actual.currentContext()
    }

    override fun onSubscribe(subscription: Subscription) {
        actual.onSubscribe(subscription)
    }

    override fun onNext(signal: O) {
        actual.onNext(signal)
    }

    override fun onError(throwable: Throwable) {
        instrumenter.end(otelContext, request, null, throwable)
        actual.onError(throwable)
    }

    override fun onComplete() {
        instrumenter.end(otelContext, request, null, null)
        actual.onComplete()
    }
}
