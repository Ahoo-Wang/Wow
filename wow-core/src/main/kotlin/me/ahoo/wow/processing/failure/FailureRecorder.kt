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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.event.EventExchange
import reactor.core.publisher.Mono

/**
 * How the processing of one event by one function ended, and so whether its message is acknowledged.
 */
enum class ProcessingOutcome {
    /** The function processed the event. Acknowledged. */
    HANDLED,

    /** The function failed and a [FailureRecorder] recorded the failure durably (for compensation). Acknowledged. */
    FAILURE_RECORDED,

    /**
     * The function failed and the [FailureRecorder] chose not to record it, for example because the function
     * disables retries. Acknowledged.
     */
    FAILURE_WAIVED,

    /**
     * The function failed and no [FailureRecorder] records failures. Acknowledged only while
     * `ackOnUnrecordedFailure` is on (the default, `wow.event.ack-on-unrecorded-failure`); otherwise left
     * unacknowledged so the bus delivers it again.
     */
    FAILURE_UNRECORDED,

    /** The function failed and the [FailureRecorder] could not record the failure. Never acknowledged. */
    RECORDING_FAILED,
    ;

    /**
     * Whether the message is acknowledged after this outcome.
     */
    fun acknowledges(ackOnUnrecordedFailure: Boolean): Boolean =
        when (this) {
            FAILURE_UNRECORDED -> ackOnUnrecordedFailure
            RECORDING_FAILED -> false
            else -> true
        }
}

/**
 * Records event-processing failures durably, so that they can be compensated (retried) later.
 *
 * The event handlers of event processors, projections, stateless sagas and snapshots call it once per function,
 * after the function's filter chain (including `RetryableFilter`) has terminated:
 * - on failure, [recordFailure] tells whether the failure was recorded; a failed recording leaves the message
 *   unacknowledged ([ProcessingOutcome.RECORDING_FAILED]);
 * - on success, [recordSuccess] lets the recorder close a failure it recorded before (for example a compensation
 *   execution that now succeeded).
 *
 * The default, [NONE], records nothing: a failure is [ProcessingOutcome.FAILURE_UNRECORDED].
 * `wow-compensation-core` provides the durable recorder.
 */
@WowSpi
interface FailureRecorder {
    /**
     * Records that the function of [exchange] failed with [error].
     *
     * @return [ProcessingOutcome.FAILURE_RECORDED], [ProcessingOutcome.FAILURE_WAIVED] or
     * [ProcessingOutcome.FAILURE_UNRECORDED] (empty means unrecorded); an error means the recording failed
     */
    fun recordFailure(exchange: EventExchange<*, *>, error: Throwable): Mono<ProcessingOutcome>

    /**
     * Called after the function of [exchange] processed its event.
     */
    fun recordSuccess(exchange: EventExchange<*, *>): Mono<Void> = Mono.empty()

    companion object {
        /** Records nothing. */
        @JvmField
        val NONE: FailureRecorder = object : FailureRecorder {
            override fun recordFailure(exchange: EventExchange<*, *>, error: Throwable): Mono<ProcessingOutcome> =
                Mono.just(ProcessingOutcome.FAILURE_UNRECORDED)

            override fun toString(): String = "FailureRecorder.NONE"
        }
    }
}
