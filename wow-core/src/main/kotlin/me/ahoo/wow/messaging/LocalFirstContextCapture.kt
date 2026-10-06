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

package me.ahoo.wow.messaging

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.metrics.captureMetricsSubscriber
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.util.ServiceLoader

/**
 * Picks, from a local-first sender's Reactor context, what its asynchronous distributed copy needs (for example the
 * trace context). A queued copy keeps only what the captures return, never the whole sender context (which can hold
 * a web request). Implementations are found through [ServiceLoader].
 */
@WowSpi
fun interface LocalFirstContextCapture {
    fun capture(source: ContextView): Context
}

/**
 * The context a distributed copy is sent in: the metrics subscriber, plus what every [LocalFirstContextCapture] found
 * through [ServiceLoader] returns.
 */
object LocalFirstContextCaptures {
    private val captures: List<LocalFirstContextCapture> by lazy {
        listOf(LocalFirstContextCapture(::captureMetricsSubscriber)) +
            ServiceLoader.load(LocalFirstContextCapture::class.java).toList()
    }

    fun capture(source: ContextView): Context =
        captures.fold(Context.empty()) { context, capture -> context.putAll(capture.capture(source)) }
}
