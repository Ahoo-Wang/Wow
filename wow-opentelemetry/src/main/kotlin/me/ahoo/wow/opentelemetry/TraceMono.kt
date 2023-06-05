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
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono

class TraceMono<T : Any, O>(
    private val parentContext: Context,
    private val instrumenter: Instrumenter<T, Unit>,
    private val request: T,
    private val source: Mono<O>,
) : Mono<O>() {
    override fun subscribe(actual: CoreSubscriber<in O>) {
        if (!instrumenter.shouldStart(parentContext, request)) {
            source.subscribe(actual)
            return
        }
        val otelContext = instrumenter.start(parentContext, request)
        otelContext.makeCurrent().use {
            source.subscribe(TraceSubscriber(instrumenter, otelContext, request, actual))
        }
    }
}
