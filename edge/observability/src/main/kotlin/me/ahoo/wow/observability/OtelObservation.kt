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

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedOutcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * A stage observed as the OpenTelemetry [span], running in [context], the span's [OtelTraceContext].
 *
 * Every span ends with its outcome as `wow.outcome`: the one set, [ObservedOutcome.ERROR] after a failure,
 * [ObservedOutcome.OK] when none was. A failure is recorded on the span, its full class name is `error.type`, and the
 * span's status is an error, except a cancellation, which only sets the outcome [ObservedOutcome.CANCELLED]. When it
 * ends, [ended] is told the outcome and the nanoseconds it took, for the stage's metric.
 */
internal class OtelObservation(
    private val span: Span,
    override val context: OtelTraceContext,
    private val ended: ((outcome: ObservedOutcome, nanos: Long) -> Unit)? = null,
) : Observation {
    private val started = System.nanoTime()

    @Volatile
    private var outcome: ObservedOutcome? = null

    override val trace: Map<String, String> get() = context.fields

    override fun outcome(outcome: ObservedOutcome) {
        this.outcome = outcome
    }

    override fun failed(error: Throwable) {
        if (error is CancellationException) {
            outcome(ObservedOutcome.CANCELLED)
            return
        }
        outcome = ObservedOutcome.ERROR
        span.setAttribute(WowAttributes.ERROR_TYPE, error.javaClass.name)
        span.recordException(error)
        span.setStatus(StatusCode.ERROR, error.message.orEmpty())
    }

    override fun end() {
        val outcome = outcome ?: ObservedOutcome.OK
        span.setAttribute(WowAttributes.OUTCOME, outcome.value)
        span.end()
        ended?.invoke(outcome, System.nanoTime() - started)
    }
}
