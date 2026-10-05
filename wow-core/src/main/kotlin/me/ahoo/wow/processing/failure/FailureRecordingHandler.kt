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
 * With [FailureRecorder.NONE] and [ackOnUnrecordedFailure] on (the defaults), a failure is logged by the default
 * error handler and acknowledged, as before 9.3.0.
 */
abstract class FailureRecordingHandler<E : EventExchange<*, *>>(
    private val chain: FilterChain<E>,
    private val errorHandler: ErrorHandler<E>,
    private val failureRecorder: FailureRecorder = FailureRecorder.NONE,
    private val ackOnUnrecordedFailure: Boolean = true,
) : Handler<E> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun handle(context: E): Mono<Void> =
        chain.filter(context)
            .materialize()
            .flatMap { signal ->
                val error = signal.throwable
                if (error == null) {
                    onSuccess(context)
                } else {
                    onFailure(context, error)
                }
            }

    private fun onSuccess(context: E): Mono<Void> =
        Mono.defer { failureRecorder.recordSuccess(context) }
            .onErrorResume { handleError(context, it) }

    private fun onFailure(context: E, error: Throwable): Mono<Void> =
        Mono.defer { failureRecorder.recordFailure(context, error) }
            .defaultIfEmpty(ProcessingOutcome.FAILURE_UNRECORDED)
            .onErrorResume { recordError ->
                val cause = if (Exceptions.isRetryExhausted(recordError)) recordError.cause else null
                error.addSuppressedOnce(cause ?: recordError)
                Mono.just(ProcessingOutcome.RECORDING_FAILED)
            }
            .flatMap { outcome ->
                if (!outcome.acknowledges(ackOnUnrecordedFailure)) {
                    withhold(context, outcome)
                }
                handleError(context, error)
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
