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
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.MessageExchange
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono

class MonoTrace<T : MessageExchange<*>>(
    private val instrumenter: Instrumenter<T, Unit>,
    private val exchange: T,
    private val chain: FilterChain<T>,
) : Mono<Void>() {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val parentContext = Context.current()
        if (!instrumenter.shouldStart(parentContext, exchange)) {
            chain.filter(exchange).subscribe(actual)
            return
        }
        val context = instrumenter.start(parentContext, exchange)
        context.makeCurrent().use {
            chain.filter(exchange)
                .subscribe(TraceFilterSubscriber(instrumenter, context, exchange, actual))
        }
    }
}

class TraceFilterSubscriber<T : MessageExchange<*>>(
    private val instrumenter: Instrumenter<T, Unit>,
    private val otelContext: Context,
    private val exchange: T,
    private val actual: CoreSubscriber<in Void>,
) : BaseSubscriber<Void>() {
    override fun currentContext(): reactor.util.context.Context {
        return actual.currentContext()
    }

    override fun hookOnSubscribe(subscription: Subscription) {
        actual.onSubscribe(this)
    }

    override fun hookOnError(throwable: Throwable) {
        try {
            otelContext.makeCurrent().use {
                actual.onError(throwable)
            }
        } finally {
            instrumenter.end(otelContext, exchange, null, throwable)
        }
    }

    override fun hookOnCancel() {
        instrumenter.end(otelContext, exchange, null, null)
    }

    override fun hookOnComplete() {
        try {
            otelContext.makeCurrent().use {
                actual.onComplete()
            }
        } finally {
            instrumenter.end(otelContext, exchange, null, null)
        }
    }
}
