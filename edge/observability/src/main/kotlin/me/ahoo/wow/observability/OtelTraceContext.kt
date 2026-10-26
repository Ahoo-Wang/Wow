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

package me.ahoo.wow.observability

import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.coroutines.ThreadContextElement
import me.ahoo.wow.spi.observation.TraceContext
import kotlin.coroutines.CoroutineContext

/**
 * The core's [TraceContext] holding an OpenTelemetry [context]: while a coroutine that carries it runs, [context] is
 * the thread's current one, so the application's own instrumentation in a decide function or a handler continues the
 * same trace. Its [fields] are what [propagator] injects of it, computed once, when the work first sends them on.
 */
internal class OtelTraceContext(val context: Context, private val propagator: TextMapPropagator) :
    TraceContext(),
    ThreadContextElement<Scope> {
    override val fields: Map<String, String> by lazy {
        HashMap<String, String>().also { propagator.inject(context, it, MapSetter) }
    }

    override fun updateThreadContext(context: CoroutineContext): Scope = this.context.makeCurrent()

    override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) = oldState.close()

    override fun toString(): String = "OtelTraceContext($context)"

    internal companion object {
        /** The context [fields] propagate on top of [parent], as [propagator] extracts it. */
        fun extract(propagator: TextMapPropagator, parent: Context, fields: Map<String, String>): Context =
            if (fields.isEmpty()) parent else propagator.extract(parent, fields, MapGetter)
    }
}

/** Writes propagation fields into a mutable map. */
internal object MapSetter : TextMapSetter<MutableMap<String, String>> {
    override fun set(carrier: MutableMap<String, String>?, key: String, value: String) {
        carrier?.put(key, value)
    }
}

/** Reads propagation fields from a map. */
internal object MapGetter : TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

    override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
}
