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
import reactor.util.context.ContextView

internal object ReactorTraceContext {
    private val contextKey = Any()

    fun get(contextView: ContextView): Context {
        if (contextView.hasKey(contextKey)) {
            return contextView.get(contextKey)
        }
        return Context.current()
    }

    fun set(
        reactorContext: reactor.util.context.Context,
        otelContext: Context,
    ): reactor.util.context.Context {
        return reactorContext.put(contextKey, otelContext)
    }
}
