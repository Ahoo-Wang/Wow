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

package me.ahoo.wow.compensation.core

import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.materialize
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId.Companion.toEventId
import me.ahoo.wow.compensation.api.RetrySpec.Companion.toSpec
import me.ahoo.wow.event.EventExchange
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.processing.failure.FailureRecorder
import me.ahoo.wow.processing.failure.ProcessingOutcome
import reactor.core.publisher.Mono
import java.time.Duration
import reactor.util.retry.Retry as ReactorRetry

fun FunctionInfo.getRetry(): Retry? {
    if (this !is MessageFunction<*, *, *>) {
        return null
    }
    return this.getAnnotation(Retry::class.java)
}

/**
 * The default retry of a failure-record send: 3 retries, backoff from 1 s up to 10 s.
 */
private val DEFAULT_RECORD_FAILURE_RETRY: ReactorRetry =
    ReactorRetry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10))

/**
 * Records event-processing failures as compensation executions, by sending commands to the compensation service:
 * - the first failure of an event function: `CreateExecutionFailed`;
 * - a failed compensation execution (the event carries a compensation ID): `ApplyExecutionFailed`;
 * - a compensation execution that succeeded: `ApplyExecutionSuccess`.
 *
 * A function whose `@Retry` is disabled is not recorded ([ProcessingOutcome.FAILURE_WAIVED]). The send is retried
 * with [recordFailureRetry] (the handler is not run again); when it still fails, the recording fails and the event
 * handler leaves the message unacknowledged ([ProcessingOutcome.RECORDING_FAILED]), so the bus delivers it again.
 *
 * The command bodies are frozen wire, identical to the 9.2 `EventCompensationFilter`.
 */
@OptIn(WowSpi::class)
class CompensationFailureRecorder(
    private val commandBus: CommandBus,
    private val recordFailureRetry: ReactorRetry = DEFAULT_RECORD_FAILURE_RETRY,
) : FailureRecorder {
    override fun recordFailure(exchange: EventExchange<*, *>, error: Throwable): Mono<ProcessingOutcome> {
        val eventFunction = exchange.getFunction() ?: return Mono.just(ProcessingOutcome.FAILURE_UNRECORDED)
        val retry = eventFunction.getRetry()
        if (retry?.enabled == false) {
            return Mono.just(ProcessingOutcome.FAILURE_WAIVED)
        }
        val errorInfo = error.toErrorInfo()
        val errorDetails = ErrorDetails(
            errorCode = errorInfo.errorCode,
            errorMsg = errorInfo.errorMsg,
            bindingErrors = errorInfo.bindingErrors,
            stackTrace = error.stackTraceToString()
        )
        val recoverable = retry.recoverable(throwableClass = error.javaClass)
        val executeAt = System.currentTimeMillis()
        val executionId = exchange.message.header.compensationId
        val command = if (executionId == null) {
            CreateExecutionFailed(
                eventId = exchange.message.toEventId(),
                function = eventFunction.materialize(),
                error = errorDetails,
                executeAt = executeAt,
                retrySpec = retry?.toSpec(),
                recoverable = recoverable
            )
        } else {
            ApplyExecutionFailed(
                id = executionId,
                error = errorDetails,
                executeAt = executeAt,
                recoverable = recoverable
            )
        }
        val commandMessage = command.toCommandMessage()
        return Mono.defer { commandBus.send(commandMessage) }
            .retryWhen(recordFailureRetry)
            .thenReturn(ProcessingOutcome.FAILURE_RECORDED)
    }

    override fun recordSuccess(exchange: EventExchange<*, *>): Mono<Void> {
        val executionId = exchange.message.header.compensationId ?: return Mono.empty()
        return Mono.defer {
            val commandMessage = ApplyExecutionSuccess(
                id = executionId,
                executeAt = System.currentTimeMillis()
            ).toCommandMessage()
            commandBus.send(commandMessage)
        }
    }
}
