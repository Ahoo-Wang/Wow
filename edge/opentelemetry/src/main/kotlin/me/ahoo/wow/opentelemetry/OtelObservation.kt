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

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import me.ahoo.wow.spi.observation.Observation
import kotlin.coroutines.cancellation.CancellationException

/**
 * A stage observed as the OpenTelemetry [span] (decision D174), running in [context], the span's [OtelTraceContext].
 *
 * Its outcome is the span's `wow.outcome` attribute; a failure is recorded on the span and makes its status an
 * error, except a cancellation, which only sets the outcome `cancelled`. When it ends, [ended] is told the outcome
 * (`error` after a failure, `ok` when none was set) and the nanoseconds it took, for the stage's metric.
 */
internal class OtelObservation(
    private val span: Span,
    override val context: OtelTraceContext,
    private val ended: ((outcome: String, nanos: Long) -> Unit)? = null,
) : Observation {
    private val started = System.nanoTime()

    @Volatile
    private var outcome: String? = null

    override val trace: Map<String, String> get() = context.fields

    override fun outcome(outcome: String) {
        this.outcome = outcome
        span.setAttribute(WowAttributes.OUTCOME, outcome)
    }

    override fun failed(error: Throwable) {
        if (error is CancellationException) {
            outcome(CANCELLED)
            return
        }
        outcome = ERROR
        span.recordException(error)
        span.setStatus(StatusCode.ERROR, error.message.orEmpty())
    }

    override fun end() {
        span.end()
        ended?.invoke(outcome ?: OK, System.nanoTime() - started)
    }

    companion object {
        const val OK: String = "ok"
        const val ERROR: String = "error"
        const val CANCELLED: String = "cancelled"
    }
}
