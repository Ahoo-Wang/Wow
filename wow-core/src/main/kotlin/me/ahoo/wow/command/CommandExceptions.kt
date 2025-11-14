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

package me.ahoo.wow.command

import jakarta.validation.ConstraintViolation
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfoCapable
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.exception.ErrorCodes.COMMAND_VALIDATION
import me.ahoo.wow.exception.ErrorCodes.DUPLICATE_REQUEST_ID
import me.ahoo.wow.exception.WowException

/**
 * Exception thrown when a duplicate request ID is detected.
 *
 * This exception occurs when attempting to process a command with a request ID
 * that has already been processed for the same aggregate, preventing duplicate
 * command execution.
 *
 * @param aggregateId the aggregate for which the duplicate was detected
 * @param requestId the duplicate request identifier
 * @param errorMsg custom error message (default provided)
 * @param cause the underlying cause (optional)
 * @see WowException
 */
class DuplicateRequestIdException(
    val aggregateId: AggregateId,
    val requestId: String,
    errorMsg: String = "Duplicate request ID[$requestId].",
    cause: Throwable? = null
) : WowException(
    errorCode = DUPLICATE_REQUEST_ID,
    errorMsg = errorMsg,
    cause = cause,
),
    NamedAggregate by aggregateId

/**
 * Exception wrapping a command result that indicates failure.
 *
 * This exception is thrown when command processing completes with an error,
 * providing access to the full command result including error details and
 * binding errors.
 *
 * @param commandResult the command result containing error information
 * @param cause the underlying cause (optional)
 * @see CommandResult
 * @see WowException
 */
class CommandResultException(
    val commandResult: CommandResult,
    cause: Throwable? = null
) : WowException(
    errorCode = commandResult.errorCode,
    errorMsg = commandResult.errorMsg,
    cause = cause,
    bindingErrors = commandResult.bindingErrors,
),
    ErrorInfoCapable {
    override val errorInfo: ErrorInfo
        get() = commandResult
}

/**
 * Exception thrown when command validation fails.
 *
 * This exception contains validation errors that occurred during command
 * processing, including constraint violations and binding errors.
 *
 * @param command the command that failed validation
 * @param errorMsg custom error message (default provided)
 * @param bindingErrors list of validation errors
 * @param cause the underlying cause (optional)
 * @see WowException
 * @see BindingError
 */
class CommandValidationException(
    val command: Any,
    errorMsg: String = "Command validation failed.",
    bindingErrors: List<BindingError> = emptyList(),
    cause: Throwable? = null
) : WowException(
    errorCode = COMMAND_VALIDATION,
    errorMsg = errorMsg,
    cause = cause,
    bindingErrors = bindingErrors,
),
    ErrorInfo {
    companion object {
        internal fun Set<ConstraintViolation<*>>.toBindingErrors(): List<BindingError> =
            this.map {
                BindingError(it.propertyPath.toString(), it.message)
            }

        fun <T> Set<ConstraintViolation<T>>.toCommandValidationException(
            command: Any,
            errorMsg: String = "Command validation failed."
        ): CommandValidationException = CommandValidationException(
            command,
            errorMsg = errorMsg,
            bindingErrors = toBindingErrors()
        )
    }
}
