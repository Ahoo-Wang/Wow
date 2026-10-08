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

package me.ahoo.wow.processing.failure

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.event.EventExchange
import me.ahoo.wow.filter.ErrorAccessor
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.messaging.handler.withholdAcknowledgement
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import reactor.core.Exceptions
import reactor.core.publisher.Mono

/**
 * The event handler of one function: runs [chain], then settles the [ProcessingOutcome] with [failureRecorder] and
 * hands a failure to [errorHandler].
 *
 * - Success: [FailureRecorder.recordSuccess]; an error there goes to [errorHandler].
 * - Failure: [FailureRecorder.recordFailure], then [errorHandler] with the processing error (a recording error is
 *   added to it as suppressed). When the outcome [does not acknowledge][ProcessingOutcome.acknowledges], the exchange
 *   [withholds its acknowledgement][withholdAcknowledgement], so the bus delivers the message again.
 *
 * A failure whose in-process retries were exhausted (`RetryableFilter`) is recorded and handled as its cause, the
 * error the function last failed with, as the wait notifier reports it.
 *
 * Each outcome is counted in [metrics] (`wow.processing.outcomes`, tag `outcome`) with [metricDescriptor].
 *
 * With [FailureRecorder.NONE] and [ackOnUnrecordedFailure] on (the defaults), a failure is logged by the default
 * error handler and acknowledged, as before 9.3.0.
 */
abstract class FailureRecordingHandler<E : EventExchange<*, *>>(
    private val chain: FilterChain<E>,
    private val errorHandler: ErrorHandler<E>,
    private val failureRecorder: FailureRecorder = FailureRecorder.NONE,
    private val ackOnUnrecordedFailure: Boolean = true,
    private val metrics: WowMetrics = WowMetrics.NONE,
) : Handler<E> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Whether a success needs settling: with [FailureRecorder.NONE] and metrics off it does nothing, so [handle] skips
     * the per-function `materialize`/`flatMap` and only resumes a failure (the event consume path runs one handler per
     * function, so those operators were on its critical path). Both are constructor values that never change, so
     * deciding once at construction is safe.
     */
    private val settlesSuccess: Boolean = failureRecorder !== FailureRecorder.NONE || metrics.enabled

    override fun handle(context: E): Mono<Void> {
        val processed = chain.filter(context)
        if (!settlesSuccess) {
            return processed.onErrorResume { error -> onFailure(context, error.retryExhaustedCause()) }
        }
        return processed
            .materialize()
            .flatMap { signal ->
                val error = signal.throwable
                if (error == null) {
                    onSuccess(context)
                } else {
                    onFailure(context, error.retryExhaustedCause())
                }
            }
    }

    /** The metric identity of the processing of [context]. */
    protected abstract fun metricDescriptor(context: E): MetricDescriptor

    private fun onSuccess(context: E): Mono<Void> {
        countOutcome(context, ProcessingOutcome.HANDLED)
        return Mono.defer { failureRecorder.recordSuccess(context) }
            .onErrorResume { handleError(context, it) }
    }

    private fun onFailure(context: E, error: Throwable): Mono<Void> =
        Mono.defer { failureRecorder.recordFailure(context, error) }
            .defaultIfEmpty(ProcessingOutcome.FAILURE_UNRECORDED)
            .onErrorResume { recordError ->
                error.addSuppressedOnce(recordError.retryExhaustedCause())
                Mono.just(ProcessingOutcome.RECORDING_FAILED)
            }
            .flatMap { outcome ->
                countOutcome(context, outcome)
                if (!outcome.acknowledges(ackOnUnrecordedFailure)) {
                    withhold(context, outcome)
                }
                handleError(context, error)
            }

    private fun countOutcome(context: E, outcome: ProcessingOutcome) {
        if (metrics.enabled) {
            metrics.processingOutcome(metricDescriptor(context), outcome.name.lowercase())
        }
    }

    private fun withhold(context: E, outcome: ProcessingOutcome) {
        log.warn {
            "Withhold the acknowledgement of message[${context.message.id}]: processing outcome [$outcome]."
        }
        context.withholdAcknowledgement()
    }

    private fun handleError(context: E, error: Throwable): Mono<Void> {
        if (context is ErrorAccessor) {
            context.setError(error)
        }
        return errorHandler.handle(context, error)
    }
}

private fun Throwable.retryExhaustedCause(): Throwable =
    if (Exceptions.isRetryExhausted(this)) cause ?: this else this

/**
 * Adds [error] as suppressed unless it is this throwable itself or an equal failure (same type and message) is
 * already suppressed: a shared or singleton handler exception is thrown again on every redelivery and must not
 * grow a suppressed entry each time.
 */
internal fun Throwable.addSuppressedOnce(error: Throwable) {
    if (error === this) {
        return
    }
    val alreadySuppressed = suppressed.any {
        it === error || (it.javaClass == error.javaClass && it.message == error.message)
    }
    if (!alreadySuppressed) {
        addSuppressed(error)
    }
}
