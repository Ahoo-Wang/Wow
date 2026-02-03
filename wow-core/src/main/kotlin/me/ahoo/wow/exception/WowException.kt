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

package me.ahoo.wow.exception

import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.RecoverableType
import java.util.concurrent.TimeoutException

/**
 * Base exception class for the Wow framework.
 *
 * WowException extends RuntimeException and implements ErrorInfo to provide
 * standardized error handling with error codes, messages, and optional binding errors.
 * It serves as the foundation for all framework-specific exceptions.
 *
 * Example usage:
 * ```kotlin
 * throw WowException(
 *     errorCode = ErrorCodes.ILLEGAL_ARGUMENT,
 *     errorMsg = "Invalid input parameter",
 *     bindingErrors = listOf(BindingError("field", "must not be null"))
 * )
 * ```
 *
 * @property errorCode the standardized error code for this exception
 * @property errorMsg the error message
 * @property bindingErrors optional list of field-level validation errors
 * @param cause the underlying cause of this exception, if any
 * @see ErrorInfo
 * @see BindingError
 */
open class WowException(
    final override val errorCode: String,
    errorMsg: String,
    cause: Throwable? = null,
    override val bindingErrors: List<BindingError> = emptyList()
) : RuntimeException(errorMsg, cause),
    ErrorInfo {
    override val message: String
        get() = errorMsg
    override val errorMsg: String
        get() {
            val superMsg = super.message.orEmpty()
            if (superMsg.isNotBlank()) {
                return superMsg
            }
            if (bindingErrors.isEmpty()) {
                return superMsg
            }
            return bindingErrors.first().msg
        }
}

/**
 * Marker interface for exceptions that can be recovered through retry mechanisms.
 *
 * Exceptions implementing this interface are classified as recoverable, meaning
 * that retrying the operation that caused the exception may succeed. This is
 * typically used for transient failures like network timeouts or temporary
 * resource unavailability.
 *
 * @see RecoverableType.RECOVERABLE
 */
interface RecoverableException

/**
 * Determines the recoverable type of this Throwable.
 *
 * This property checks if the exception can be recovered through retry mechanisms
 * by examining the exception's class hierarchy and any registered classifications.
 *
 * @see RecoverableException
 * @see RecoverableType
 */
val Throwable.recoverable: RecoverableType
    get() {
        return this.javaClass.recoverable
    }

/**
 * Determines the default recoverable type for this exception class.
 *
 * This property provides default recoverable classifications based on the exception
 * class hierarchy. It first checks for explicit registrations, then applies default
 * rules for known recoverable exception types.
 *
 * Default classifications:
 * - Exceptions implementing RecoverableException → RECOVERABLE
 * - TimeoutException and subclasses → RECOVERABLE
 * - All others → UNKNOWN
 *
 * @return the recoverable type classification for this exception class
 * @see RecoverableExceptionRegistrar
 * @see RecoverableType
 */
val Class<out Throwable>.recoverable: RecoverableType
    get() {
        RecoverableExceptionRegistrar.getRecoverableType(this)?.let {
            return it
        }
        return when {
            RecoverableException::class.java.isAssignableFrom(this) -> RecoverableType.RECOVERABLE
            TimeoutException::class.java.isAssignableFrom(this) -> RecoverableType.RECOVERABLE
            else -> RecoverableType.UNKNOWN
        }
    }

/**
 * Determines recoverable type considering Retry annotation configuration.
 *
 * This function evaluates the recoverable type of an exception class in the context
 * of a Retry annotation. The Retry annotation can explicitly mark certain exceptions
 * as recoverable or unrecoverable, overriding the default classifications.
 *
 * Priority order:
 * 1. Explicitly listed in retry.recoverable → RECOVERABLE
 * 2. Explicitly listed in retry.unrecoverable → UNRECOVERABLE
 * 3. Default classification for the exception class
 *
 * @param throwableClass the exception class to evaluate
 * @return the recoverable type considering retry configuration
 * @see Retry
 * @see RecoverableType
 */
fun Retry?.recoverable(throwableClass: Class<out Throwable>): RecoverableType {
    if (this == null) {
        return throwableClass.recoverable
    }

    if (recoverable.any { it.java == throwableClass }) {
        return RecoverableType.RECOVERABLE
    }
    if (unrecoverable.any { it.java == throwableClass }) {
        return RecoverableType.UNRECOVERABLE
    }
    return throwableClass.recoverable
}
