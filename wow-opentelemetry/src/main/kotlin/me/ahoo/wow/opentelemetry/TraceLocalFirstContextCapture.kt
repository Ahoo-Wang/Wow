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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.messaging.LocalFirstContextCapture
import reactor.util.context.Context
import reactor.util.context.ContextView

/**
 * Carries the sender's trace context (or the current one) to a local-first distributed copy, so its send span keeps
 * the sender's trace as parent.
 */
@OptIn(WowSpi::class)
class TraceLocalFirstContextCapture : LocalFirstContextCapture {
    override fun capture(source: ContextView): Context =
        ReactorTraceContext.set(Context.empty(), ReactorTraceContext.get(source))
}
